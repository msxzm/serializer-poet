package com.msxzm.core.serializer;

import com.google.auto.service.AutoService;
import com.msxzm.base.GeneratedFile;
import com.msxzm.base.serializer.Serializable;
import com.msxzm.base.serializer.SerializerField;
import com.squareup.javapoet.*;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Consumer;

/**
 * 序列化处理类
 * @author zenghongming
 * @date 2020/1/13 15:53
 */
@AutoService(Processor.class)
public class SerializerProcessor extends AbstractProcessor {
    /** 元素后缀 */
    private static final String ELEMENT = "Element";
    private static final String SOURCE_PATH = Utils.fixPath("src/gen/java");
    private static final String TARGET_PATH = Utils.fixPath("target/generated-sources/annotations");

    /** 编译信息输出 */
    private Messager messager;
    /** 抽象语法树 */
    private JavacTrees trees;
    /** 抽象语法树构造 */
    private TreeMaker treeMaker;
    /** 元素 */
    private Elements elements;
    /** 类型 */
    private Types types;
    private Filer filer;


    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        Context context = ((JavacProcessingEnvironment) processingEnv).getContext();
        this.trees = JavacTrees.instance(processingEnv);
        this.treeMaker = TreeMaker.instance(context);
        this.messager = processingEnv.getMessager();
        this.types = processingEnv.getTypeUtils();
        this.elements = processingEnv.getElementUtils();
        this.filer = processingEnv.getFiler();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        JavaSourceWrapper javaSourceWrapper = new JavaSourceWrapper();
        // 找出所有有Serializable注解的类,
        // 即使没有SerializerField注解的类也需要生成序列化辅助类，比如某些没有需序列化的父类
        roundEnv.getRootElements().forEach(e -> {
            TypeElement element = (TypeElement) e;
            if (element.getAnnotation(Serializable.class) == null) {
                return;
            }
            javaSourceWrapper.computeIfAbsent(element);
        });

        Set<? extends Element> serialElement = roundEnv.getElementsAnnotatedWith(SerializerField.class);
        // 找出所有有SerializerField的字段
        serialElement.forEach(element -> {
            JCTree jcVariableTree = trees.getTree(element);
            jcVariableTree.accept(new TreeTranslator() {
                @Override
                public void visitVarDef(JCVariableDecl jcVariableDecl) {
                    super.visitVarDef(jcVariableDecl);
                    // 当前字段的类元素
                    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
                    JavaClassWrapper classWrapper = javaSourceWrapper.computeIfAbsent(enclosingElement);
                    classWrapper.addVariableDecl(element, jcVariableDecl);
                }
            });
        });
        javaSourceWrapper.forEach(classWrapper -> {
            classWrapper.forEach(variableDecl -> {
                // 增加Getter、Setter方法
            });

            // 自定义序列化
            boolean isCustomized = classWrapper.isCustomizedSerialize();
            // 增加write方法
            try {
                classWrapper.addMethodSpec(makeReadWriteMethodSpec(classWrapper, SerializerBound.WRITE, isCustomized));
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.WARNING, classWrapper.getSimpleName() + " : " + e.getMessage());
            }
            // 增加read方法
            try {
                classWrapper.addMethodSpec(makeReadWriteMethodSpec(classWrapper, SerializerBound.READ, isCustomized));
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.WARNING, classWrapper.getSimpleName() + " : " + e.getMessage());
            }

            // 写入到文件中
            classWrapper.writeToFile();
//            classWrapper.writeToFile(SOURCE_PATH);
        });
        return true;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> annotations = new LinkedHashSet<>();
        annotations.add(Serializable.class.getCanonicalName());
        return annotations;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * 生成read write方法
     * @param classWrapper 类包装
     * @param bound 序列化方向 read write
     * @param isCustomized 自定义的序列化
     * @return 方法定义
     */
    private MethodSpec.Builder makeReadWriteMethodSpec(JavaClassWrapper classWrapper, SerializerBound bound, boolean isCustomized) {
        // read write方法
        MethodSpec.Builder methodSpec = MethodSpec.methodBuilder(bound.serializerExec)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(void.class)
                .addException(IOException.class)
                .addParameter(bound.stream, bound.paramName)
                .addParameter(ClassName.get(classWrapper.element), "instance");

        try {
            // 看父类是否实现了Serializable接口
            if (isSerializableAssignableFrom(classWrapper.element.asType())) {
                // super read write
                TypeMirror superClass = findSerializableParentClass(classWrapper.element.asType());
                if (superClass != null) {
                    TypeMirror type = types.erasure(superClass);
                    String superClassName = type.toString() + "IOSerializer";
                    methodSpec.addStatement("$T.$L($L, $L)", ClassName.bestGuess(superClassName), bound.serializerExec, bound.paramName, "instance");
                }
            }
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.WARNING, classWrapper.getSimpleName() + " : " + e.getMessage());
        }
        // 自定义复写了writeTo readFrom的，序列化直接调用对象的writeTo readFrom
        if (isCustomized) {
            methodSpec.addStatement("instance.$L($L)", bound.accessName, bound.paramName);
        } else {
            // read write 字段
            classWrapper.forEach(variableWrapper -> {
                TypeMirror type = variableWrapper.element.asType();
                String variable = variableWrapper.variable.name.toString();
                // Array、Collection、Map
                if (isArray(type) || isCollection(type) || isMap(type)) {
                    serializeVariable(methodSpec, variableWrapper, bound);
                } else if (isPrimitiveType(type)){
                    if (bound == SerializerBound.WRITE) {
                        Class<?> clazz = getPrimitiveClass(type);
                        String get = Boolean.class == clazz || boolean.class == clazz ? "is" : "get";
                        String getVariable = "instance." + get + Utils.toUpperCaseFirst(variable) + "()";
                        writeVariable(methodSpec, variableWrapper.element.asType(), getVariable);
                    } else {
                        if (isWrapper(type)) {
                            // 先读一个标志
                            methodSpec.beginControlFlow("if ($L)", doReadAnPrimitive(boolean.class));
                            // 如果不为null则继续读
                            methodSpec.addStatement("instance.set$L($L)", Utils.toUpperCaseFirst(variable), doReadAnPrimitive(getPrimitiveClass(type)));
                            methodSpec.nextControlFlow("else");
                            methodSpec.addStatement("instance.set$L(null)", Utils.toUpperCaseFirst(variable));
                            methodSpec.endControlFlow();
                        } else {
                            methodSpec.addStatement("instance.set$L($L)", Utils.toUpperCaseFirst(variable), doReadAnPrimitive(getPrimitiveClass(type)));
                        }
                    }
                } else if (isSerializable(type)){
                    serializeVariable(methodSpec, variableWrapper, bound);
                } else {
                    if (bound == SerializerBound.WRITE) {
                        String getVariable = "instance.get" + Utils.toUpperCaseFirst(variable) + "()";
                        writeVariable(methodSpec, variableWrapper.element.asType(), getVariable);
                    } else {
                        methodSpec.addStatement("instance.set$L($L.$L())", Utils.toUpperCaseFirst(variable), SerializerBound.READ.paramName, SerializerBound.READ.serializerExec);
                    }
                }
            });
        }
        return methodSpec;
    }

    /**
     * 序列化一个变量
     * @param statements 方法体stats
     * @param variableWrapper 变量包装
     * @param bound 序列化方向
     */
    private void serializeVariable(MethodSpec.Builder statements, VariableWrapper variableWrapper, SerializerBound bound) {
        TypeMirror type = variableWrapper.element.asType();
        String variable = variableWrapper.variable.name.toString();
        if (bound == SerializerBound.WRITE) {
            String getVariable = "instance.get" + Utils.toUpperCaseFirst(variable) + "()";
            statements.addStatement("$T $L = $L", ClassName.get(type), variable, getVariable);
            writeVariable(statements, variableWrapper.element.asType(), variable);
        } else {
            statements.addStatement("$T $L", ClassName.get(type), variable);
            readVariable(statements, variableWrapper.element.asType(), variable);
            statements.addStatement("instance.set$L($L)", Utils.toUpperCaseFirst(variable), variable);
        }
    }

    /**
     * 写一个变量(递归一直到基础类型，或者自定义序列化对象)
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void writeVariable(MethodSpec.Builder statements, TypeMirror type, String variable) {
        // 数组
        if (isArray(type)) {
            writeArray(statements, (ArrayType) type, variable);
            return;
        }
        // Collection
        if (isCollection(type)) {
            writeCollection(statements, type, variable);
            return;
        }
        // Map
        if (isMap(type)) {
            writeMap(statements, type, variable);
            return;
        }
        // 基础类型
        if (isPrimitiveType(type)) {
            writePrimitive(statements, type, variable);
            return;
        }
        // 自定义序列化对象
        if (isSerializable(type) && !isAbstract(type)) {
            writeSerializable(statements, type, variable);
            return;
        }
        // 其他类型
        writeObject(statements, variable);
    }

    /**
     * 写基础类型
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variableName 变量
     */
    private void writePrimitive(MethodSpec.Builder statements, TypeMirror type, String variableName) {
        // 包装类型得先写个null
        if (isWrapper(type)) {
            // 先写一个布尔值标记集合是否为null
            writePrimitive(statements, boolean.class, variableName + " != null");
            statements.beginControlFlow("if ($L != null)", variableName);
            // 如果不为null则写值
            writePrimitive(statements, getPrimitiveClass(type), variableName);
            statements.endControlFlow();
        } else {
            writePrimitive(statements, getPrimitiveClass(type), variableName);
        }
    }

    /**
     * 写一个基础类型
     * @param statements 方法体stats
     * @param primitiveClass 基础类型类
     * @param variableName 变量
     */
    private void writePrimitive(MethodSpec.Builder statements, Class<?> primitiveClass, String variableName) {
        String writeAccess = "write" + Utils.toUpperCaseFirst(primitiveClass.getSimpleName());
        statements.addStatement("$L.$L($L)", SerializerBound.WRITE.paramName, writeAccess, variableName);
    }

    /**
     * 写一个数组
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variableName 变量
     */
    private void writeArray(MethodSpec.Builder statements, ArrayType type, String variableName) {
        // 先写一个布尔值标记集合是否为null
        writePrimitive(statements, boolean.class, variableName + " != null");
        // 如果不为null则展开数组
        statements.beginControlFlow("if ($L != null)", variableName);
        // 先写一个长度
        String arrayLen = variableName + ".length";
        writePrimitive(statements, int.class, arrayLen);
        // 然后for循环
        String stepName = variableName + "_i";
        statements.beginControlFlow("for (int $L = 0; $L < $L; ++$L)", stepName, stepName, arrayLen, stepName);
        // for循环内部
        String elementName = variableName + ELEMENT;
        // 数组子元素类型
        Type elementType = type.elemtype;
        // 访问数组元素
        String element = variableName + "[" + stepName + "]";
        // 多维数组递归
        if (isArray(elementType)) {
            // 创建一个局部变量接一下
            statements.addStatement("$T $L = $L", ClassName.get(elementType), elementName, element);
            writeArray(statements, (ArrayType) elementType, elementName);
            statements.endControlFlow();
            statements.endControlFlow();
            return;
        }
        // 基础类型或自定义序列化对象直接写，其他类型创建一个局部变量
        if (isPrimitiveType(elementType)) {
            writePrimitive(statements, elementType, element);
        } else if (isSerializable(elementType) && !isAbstract(elementType)) {
            writeSerializable(statements, elementType, element);
        } else {
            // 创建一个局部变量接一下
            statements.addStatement("$T $L = $L", ClassName.get(elementType), elementName, element);
            writeVariable(statements, elementType, elementName);
        }
        statements.endControlFlow();
        statements.endControlFlow();
    }

    /**
     * 写一个Collection
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variableName 变量
     */
    private void writeCollection(MethodSpec.Builder statements, TypeMirror type, String variableName) {
        // 先写一个布尔值标记集合是否为null
        writePrimitive(statements, boolean.class, variableName + " != null");
        // 如果不为null则展开List
        statements.beginControlFlow("if ($L != null)", variableName);
        // 写长度
        writePrimitive(statements, int.class, variableName + ".size()");
        // for展开
        String elementName = variableName + ELEMENT;
        Type elementType = ((Type) type).getTypeArguments().head;
        statements.beginControlFlow("for ($T $L : $L)", ClassName.get(elementType), elementName, variableName);
        // write Value
        writeVariable(statements, elementType, elementName);
        statements.endControlFlow();
        statements.endControlFlow();
    }

    /**
     * 写一个map
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variableName 变量
     */
    private void writeMap(MethodSpec.Builder statements, TypeMirror type, String variableName) {
        // 先写一个布尔值标记集合是否为null
        writePrimitive(statements, boolean.class, variableName + " != null");
        // 如果不为null则展开Map
        statements.beginControlFlow("if ($L != null)", variableName);
        // 写长度
        writePrimitive(statements, int.class, variableName + ".size()");
        // 取泛型
        Type keyType = ((Type) type).getTypeArguments().head;
        Type valueType = ((Type) type).getTypeArguments().last();
        // Entry
        String entryName = variableName + ELEMENT;
        TypeName keyTypeName = ClassName.get(keyType);
        TypeName valueTypeName = ClassName.get(valueType);
        statements.beginControlFlow("for ($T<$T, $T> $L : $L.entrySet())", Entry.class, keyTypeName, valueTypeName, entryName, variableName);
        // write Key
        writeMapArgs(statements, variableName + "Key", keyType, entryName + ".getKey()");
        // write Value
        writeMapArgs(statements, variableName + "Value", valueType, entryName + ".getValue()");

        statements.endControlFlow();
        statements.endControlFlow();
    }

    /**
     * 写map的参数
     * @param statements 方法体stats
     * @param name 名字
     * @param type 类型
     * @param variable 变量
     */
    private void writeMapArgs(MethodSpec.Builder statements, String name, Type type, String variable) {
        if (type.getKind().isPrimitive()) {
            writePrimitive(statements, getPrimitiveClass(type), variable);
        } else {
            statements.addStatement("$T $L = $L", ClassName.get(type), name, variable);
            writeVariable(statements, type, name);
        }
    }

    /**
     * 写可序列化的自定义对象
     * @param statements 方法体stats
     * @param variable 变量
     */
    private void writeSerializable(MethodSpec.Builder statements, TypeMirror type, String variable) {
        // 先写一个布尔值标记集合是否为null
        writePrimitive(statements, boolean.class, variable + " != null");
        statements.beginControlFlow("if ($L != null)", variable);
        statements.addStatement("$L.$L($L)", SerializerBound.WRITE.paramName, SerializerBound.WRITE.serializerExec, variable);
        statements.endControlFlow();
    }

    /**
     * 写一个对象
     * @param statements 方法体stats
     * @param variable 变量
     */
    private void writeObject(MethodSpec.Builder statements, String variable) {
        statements.addStatement("$L.write($L)", SerializerBound.WRITE.paramName, variable);
    }

    /**
     * 读一个变量
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void readVariable(MethodSpec.Builder statements, TypeMirror type, String variable) {
        // 数组
        if (isArray(type)) {
            readArray(statements, (ArrayType) type, variable);
            return;
        }
        // Collection
        if (isCollection(type)) {
            readCollection(statements, type, variable);
            return;
        }
        // Map
        if (isMap(type)) {
            readMap(statements, type, variable);
            return;
        }
        // 基础类型
        if (isPrimitiveType(type)) {
            readPrimitive(statements, type, variable);
            return;
        }
        // 自定义序列化对象
        if (isSerializable(type) && !isAbstract(type)) {
            readSerializable(statements, type, variable);
            return;
        }
        // 其他类型
        readObject(statements, variable);
    }

    /**
     * 读基础类型
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void readPrimitive(MethodSpec.Builder statements, TypeMirror type, String variable) {
        // 包装类型得先读个布尔值
        if (isWrapper(type)) {
            // 先读一个标志
            statements.beginControlFlow("if ($L)", doReadAnPrimitive(boolean.class));
            // 如果不为null则继续读
            readPrimitive(statements, getPrimitiveClass(type), variable);
            statements.nextControlFlow("else");
            readNull(statements, variable);
            statements.endControlFlow();
        } else {
            readPrimitive(statements, getPrimitiveClass(type), variable);
        }
    }

    /**
     * 读基础类型
     * @param statements 方法体stats
     * @param primitiveClass 基础类型
     * @param variable 变量
     */
    private void readPrimitive(MethodSpec.Builder statements, Class<?> primitiveClass, String variable) {
        // xxx = xxx;
        statements.addStatement("$L = $L", variable, doReadAnPrimitive(primitiveClass));
    }

    /**
     * 读一个基础类型
     * @param primitiveClass 基础类型
     * @return JCExpressionStatement
     */
    private String doReadAnPrimitive(Class<?> primitiveClass) {
        String readAccess = "read" + Utils.toUpperCaseFirst(primitiveClass.getSimpleName());
        return SerializerBound.READ.paramName + "." + readAccess + "()";
    }

    /**
     * 读一个null
     * @param statements 方法体stats
     * @param variable 变量
     */
    private void readNull(MethodSpec.Builder statements, String variable) {
        statements.addStatement("$L = null", variable);
    }

    /**
     * 读一个数组
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void readArray(MethodSpec.Builder statements, ArrayType type, String variable) {
        // 数组子元素类型
        Type elementType = type.elemtype;
        TypeName arrayType = ClassName.get(elementType);
        // 先读一个标志
        statements.beginControlFlow("if ($L)", doReadAnPrimitive(boolean.class));
        // 如果不为null则展开数组
        String lenName = variable + "Len";
        // 读出数组长度
        statements.addStatement("int $L = $L", lenName, doReadAnPrimitive(int.class));
        // 再new一个数组
        statements.addStatement("$L = new $T[$L]", variable, arrayType, lenName);
        // 索引 name_i
        String stepName = variable + "_i";
        // 然后for循环
        statements.beginControlFlow("for (int $L = 0; $L < $L; ++$L)", stepName, stepName, lenName, stepName);
        // 子元素名称
        String elementName = variable + ELEMENT;

        // 多维数组递归
        if (isArray(elementType)) {
            statements.addStatement("$T $L = null", ClassName.get(elementType), variable);
            readArray(statements, (ArrayType) elementType, elementName);
            statements.addStatement("$L[$L] = $L", variable, stepName, elementName);
            statements.endControlFlow();
            statements.endControlFlow();
            return;
        }
        // 基础类型直接读
        if (isPrimitiveType(elementType)) {
            if (isWrapper(type)) {
                // 先读一个标志
                statements.beginControlFlow("if ($L)", doReadAnPrimitive(boolean.class));
                // 如果不为null则继续读
                statements.addStatement("$L[$L] = $L", variable, stepName, doReadAnPrimitive(getPrimitiveClass(elementType)));
                statements.nextControlFlow("else");
                statements.addStatement("$L[$L] = null", variable, stepName);
                statements.endControlFlow();
            } else {
                statements.addStatement("$L[$L] = $L", variable, stepName, doReadAnPrimitive(getPrimitiveClass(elementType)));
            }
        } else if (isSerializable(elementType) && !isAbstract(elementType)) {
            statements.addStatement("$T $L", elementType, elementName);
            readSerializable(statements, elementType, elementName);
            statements.addStatement("$L[$L] = $L", variable, stepName, elementName);
        } else if (isCollection(elementType)){
            statements.addStatement("$T $L", elementType, elementName);
            readCollection(statements, elementType, elementName);
            statements.addStatement("$L[$L] = $L", variable, stepName, elementName);
        } else if (isMap(elementType)) {
            statements.addStatement("$T $L", elementType, elementName);
            readMap(statements, elementType, elementName);
            statements.addStatement("$L[$L] = $L", variable, stepName, elementName);
        } else {
            statements.addStatement("$L[$L] = $L.read()", variable, stepName, SerializerBound.READ.paramName);
        }
        statements.endControlFlow();
        statements.nextControlFlow("else");
        readNull(statements, variable);
        statements.endControlFlow();
    }

    /**
     * 获取集合类型
     * @param type 类型
     * @return TypeName
     */
    private Class<?> getCollectionType(Type type) {
        if (isSet(type)) {
            return HashSet.class;
        }
        if (isQueue(type)) {
            return ArrayDeque.class;
        }
        return ArrayList.class;
    }

    /**
     * 读一个Collection
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void readCollection(MethodSpec.Builder statements, TypeMirror type, String variable) {
        statements.beginControlFlow("if ($L)", doReadAnPrimitive(boolean.class));
        // 集合类型
        Type collectionType = (Type) type;
        // for展开
        Type elementType = collectionType.getTypeArguments().head;
        // 泛型参数
        TypeName listImpl;
        // 读出数组长度
        String lenName = variable + "Len";
        statements.addStatement("int $L = $L", lenName, doReadAnPrimitive(int.class));
        // 声明的是接口
        if (collectionType.isInterface()) {
            listImpl = ClassName.get(getCollectionType(collectionType));
            // new一个List
            statements.addStatement("$L = new $L<>($L)", variable, listImpl, lenName);
        } else {
            listImpl = ClassName.get(types.erasure(collectionType));
            // new一个List
            statements.addStatement("$L = new $L<>()", variable, listImpl);
        }
        // 索引 name_i
        String stepName = variable + "_i";
        // for循环
        statements.beginControlFlow("for (int $L = 0; $L < $L; ++$L)", stepName, stepName, lenName, stepName);
        // 如果是基础类型直接add
        if (elementType.getKind().isPrimitive()) {
            statements.addStatement("$L.add($L)", variable, doReadAnPrimitive(getPrimitiveClass(elementType)));
        } else {
            String elementName = variable + ELEMENT;
            statements.addStatement("$T $L", ClassName.get(elementType), elementName);
            readVariable(statements, elementType, elementName);
            statements.addStatement("$L.add($L)", variable, elementName);
        }
        statements.endControlFlow();
        statements.nextControlFlow("else");
        readNull(statements, variable);
        statements.endControlFlow();
    }

    /**
     * 读一个map
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void readMap(MethodSpec.Builder statements, TypeMirror type, String variable) {
        statements.beginControlFlow("if ($L)", doReadAnPrimitive(boolean.class));
        // 读出数组长度
        String lenName = variable + "Len";
        statements.addStatement("int $L = $L", lenName, doReadAnPrimitive(int.class));
        // 集合类型
        Type mapType = (Type) type;
        Type keyType = mapType.getTypeArguments().head;
        Type valueType = mapType.getTypeArguments().last();
        // 泛型参数
        ListBuffer<JCExpression> typeArgs = new ListBuffer<>();
        mapType.getTypeArguments().forEach(t -> typeArgs.append(treeMaker.Type(t)));
        TypeName mapImpl;
        // 声明的是接口，则统一用HashMap实例化
        if (mapType.isInterface()) {
            mapImpl = ClassName.get(HashMap.class);
            // new一个Map
            statements.addStatement("$L = new $L<>($L)", variable, mapImpl, lenName);
        } else {
            mapImpl = ClassName.get(types.erasure(mapType));
            // new一个Map
            statements.addStatement("$L = new $L<>()", variable, mapImpl);
        }
        // for展开
        // 索引 name_i
        String stepName = variable + "_i";
        statements.beginControlFlow("for (int $L = 0; $L < $L; ++$L)", stepName, stepName, lenName, stepName);
        String keyName = variable + "Key";
        String valueName = variable + "Value";

        statements.addStatement("$T $L", ClassName.get(keyType), keyName);
        readVariable(statements, keyType, keyName);
        statements.addStatement("$T $L", ClassName.get(valueType), valueName);
        readVariable(statements, valueType, valueName);
        statements.addStatement("$L.put($L, $L)", variable, keyName, valueName);

        statements.endControlFlow();
        statements.nextControlFlow("else");
        readNull(statements, variable);
        statements.endControlFlow();
    }

    /**
     * 读一个自定义序列化类型
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void readSerializable(MethodSpec.Builder statements, TypeMirror type, String variable) {
        // 先读一个bool
        statements.beginControlFlow("if ($L)", doReadAnPrimitive(boolean.class));
        statements.addStatement("$L = $L.$L()", variable, SerializerBound.READ.paramName, SerializerBound.READ.serializerExec);
        statements.nextControlFlow("else");
        readNull(statements, variable);
        statements.endControlFlow();
    }

    /**
     * 读一个对象
     * @param statements 方法体stats
     * @param variable 变量
     */
    private void readObject(MethodSpec.Builder statements, String variable) {
        statements.addStatement("$L = $L.read()", variable, SerializerBound.READ.paramName);
    }

    /**
     * 是否是Serializable接口的实现
     * @param type element types
     * @return 是 true
     */
    private boolean isSerializableAssignableFrom(TypeMirror type) {
        // 递归查找父类
        for (TypeMirror superType : types.directSupertypes(type)) {
            if (isSerializable(superType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否可序列化(以Serializable注解为准)
     * @param type element types
     * @return 可以 true
     */
    private boolean isSerializable(TypeMirror type) {
        if (types.asElement(type).getAnnotation(Serializable.class) != null) {
            return true;
        }
        // 递归查找父类
        for (TypeMirror superType : types.directSupertypes(type)) {
            if (isSerializable(superType)) {
                return true;
            }
        }
        return false;
    }

    private TypeMirror findSerializableClass(TypeMirror type) {
        // 当前类有Serializable注解直接返回当前类的类名
        Element element = types.asElement(type);
        if (element.getAnnotation(Serializable.class) != null) {
            return type;
        }
        // 递归查找父类
        return findSerializableParentClass(type);
    }

    private TypeMirror findSerializableParentClass(TypeMirror type) {
        for (TypeMirror superType : types.directSupertypes(type)) {
            TypeMirror result = findSerializableClass(superType);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * 是否是某个类的子类（包括本身）
     * @param type 类型
     * @param clazz 基类
     * @return 是 true
     */
    private boolean isAssignableFrom(TypeMirror type, Class<?> clazz) {
        // 先擦除泛型
        TypeMirror erasureType = types.erasure(type);
        if (erasureType.toString().equals(clazz.getTypeName())) {
            return true;
        }
        // 递归查找父类
        for (TypeMirror superType : types.directSupertypes(type)) {
            if (isAssignableFrom(superType, clazz)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否是抽象类
     * @param type 类型
     * @return 是 true
     */
    private boolean isAbstract(TypeMirror type) {
        return types.asElement(type).getModifiers().contains(Modifier.ABSTRACT);
    }

    /**
     * 是否基础类型（包含包装类型）
     * @param type 类型
     * @return 是 true
     */
    private boolean isPrimitiveType(TypeMirror type) {
        // 基础类型
        if (type.getKind().isPrimitive()) {
            return true;
        }
        //擦除一下泛型
        TypeMirror erasureType = types.erasure(type);
        String typeName = Utils.getTypeName(erasureType.toString());
        return Utils.WRAPPER_PRIMITIVE_MAP.containsKey(typeName);
    }

    /**
     * 是否是包装类型
     * @param type 类型
     * @return 是 true
     */
    private boolean isWrapper(TypeMirror type) {
        //擦除一下泛型
        TypeMirror erasureType = types.erasure(type);
        String typeName = Utils.getTypeName(erasureType.toString());
        return Utils.WRAPPER_PRIMITIVE_MAP.containsKey(typeName);
    }

    /**
     * 获取基础类型类
     * @param type 类型
     * @return Class
     */
    private Class<?> getPrimitiveClass(TypeMirror type) {
        String typeName = Utils.getTypeName(type.toString());
        Class<?> clazz = Utils.PRIMITIVE_MAP.get(typeName);
        if (clazz != null) {
            return clazz;
        }
        return Utils.WRAPPER_PRIMITIVE_MAP.get(typeName);
    }

    /**
     * 是否是数组
     * @param type 类型
     * @return 是 true
     */
    private boolean isArray(TypeMirror type) {
        return type.getKind() == TypeKind.ARRAY;
    }

    /**
     * 是否是集合
     * @param type 类型
     * @return 是 true
     */
    private boolean isCollection(TypeMirror type) {
        return isAssignableFrom(type, Collection.class);
    }

    /**
     * 是否是Map
     * @param type 类型
     * @return 是 true
     */
    private boolean isMap(TypeMirror type) {
        return isAssignableFrom(type, Map.class);
    }

    /**
     * 是否是Set
     * @param type 类型
     * @return 是 true
     */
    private boolean isSet(TypeMirror type) {
        return isAssignableFrom(type, Set.class);
    }

    /**
     * 是否是Queue
     * @param type 类型
     * @return 是 true
     */
    private boolean isQueue(TypeMirror type) {
        return isAssignableFrom(type, Queue.class);
    }

    /**
     * 输出警告信息
     * @param className 类名
     * @param msg 警告信息
     */
    private void printWarning(String className, String msg) {
        messager.printMessage(Diagnostic.Kind.WARNING, "Class: " + className + " [ " + msg + " ]");
    }

    /**
     * 输出错误信息
     * @param className 类名
     * @param msg 错误信息
     */
    private void printError(String className, String msg) {
        messager.printMessage(Diagnostic.Kind.ERROR, "Class: " + className + " [ " + msg + " ]");
    }

    /** java文件包装 */
    private class JavaSourceWrapper {
        /** 类组 */
        private Map<String, JavaClassWrapper> classMap = new HashMap<>();

        /**
         * 放入一个类定义
         * @param element 类元素
         * @return 类包装
         */
        JavaClassWrapper computeIfAbsent(TypeElement element) {
            JCClassDecl jcClassDecl = trees.getTree(element);
            return classMap.computeIfAbsent(jcClassDecl.getSimpleName().toString(), k -> new JavaClassWrapper(element, jcClassDecl));
        }

        /**
         * 遍历类
         * @param consumer Consumer<JavaClassWrapper>
         */
        void forEach(Consumer<JavaClassWrapper> consumer) {
            classMap.values().forEach(consumer);
        }
    }

    /** java类包装 */
    private class JavaClassWrapper {
        /** 类元素 */
        TypeElement element;
        /** 类定义 */
        JCClassDecl classDecl;
        /** 变量字段组 */
        List<VariableWrapper> variableList = List.nil();
        /** 类 */
        TypeSpec.Builder classSpec;
        /** 类名 */
        String className;

        JavaClassWrapper(TypeElement element, JCClassDecl classDecl) {
            this.element = element;
            this.classDecl = classDecl;
            this.className = getSimpleName() + "IOSerializer";
            this.classSpec = TypeSpec.classBuilder(className)
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                    .addAnnotation(GeneratedFile.class);
        }

        /**
         * 是否不可序列化
         * @return 不可序列化 true
         */
        boolean unableSerializable(Element element) {
            TypeMirror type = element.asType();
            // 看是否有SerializerField注解
            SerializerField annotation = element.getAnnotation(SerializerField.class);
            // 自定义强制序列化的
            if (annotation != null && annotation.uncheck()) {
                return false;
            }
            // 如果是数组
            if (type instanceof ArrayType) {
                type = Utils.erasureArray((ArrayType) type);
            }
            // 基础类型都支持序列化
            if (isPrimitiveType(type)) {
                return false;
            }
            // 集合
            if (type.getKind() == TypeKind.DECLARED) {
                // Map、List 递归展开
                if (isCollection(type) || isMap(type)) {
                    Result<Boolean> unableSerialize = new Result<>(false);
                    DeclaredType declaredType = (DeclaredType) type;
                    declaredType.getTypeArguments().forEach(typeArgs -> {
                        if (typeArgs.getKind() != TypeKind.TYPEVAR && unableSerializable(types.asElement(typeArgs))) {
                            unableSerialize.value = true;
                        }
                    });
                    return unableSerialize.value;
                }
            }
            // 自定义序列化对象
            return !isSerializable(type);
        }

        /**
         * 增加变量定义
         * @param element 变量元素
         * @param jcVariableDecl 变量定义
         */
        void addVariableDecl(Element element, JCVariableDecl jcVariableDecl) {
            if (element.getModifiers().contains(Modifier.FINAL)) {
                printError(classDecl.getSimpleName().toString(), "用final修饰的字段无法序列化，请检查! Variable: " + jcVariableDecl.getName());
                return;
            }
            if (unableSerializable(element)) {
                printError(classDecl.getSimpleName().toString(), "无法序列化的对象，请检查! Variable: " + jcVariableDecl.getName());
                return;
            }
            this.variableList = variableList.append(new VariableWrapper(element, jcVariableDecl));
        }

        /**
         * 增加方法
         * @param methodSpec 方法定义
         */
        void addMethodSpec(MethodSpec.Builder methodSpec) {
            classSpec.addMethod(methodSpec.build());
        }

        /**
         * 是否存在方法定义
         * @param methodName 方法名
         * @param params 参数
         * @return 是 true
         */
        boolean hasMethodDecl(String methodName, Class<?>...params) {
            // 构造方法签名
            StringBuilder builder = new StringBuilder(methodName);
            for (Class<?> param : params) {
                builder.append("_");
                builder.append(param.getSimpleName().replaceAll(Utils.CLASS_ARRAY_REGEX, Utils.EMPTY));
            }
            Result<Boolean> result = new Result<>(false);
            String methodSignature = builder.toString();
            // 直接遍历类的定义
            classDecl.defs.forEach(def -> {
                if (result.value) {
                    return;
                }
                if (def.getTag() == Tag.METHODDEF) {
                    JCMethodDecl jcMethodDecl = (JCMethodDecl) def;
                    if (methodSignature.equals(Utils.getMethodSignature(jcMethodDecl))) {
                        result.value = true;
                    }
                }
            });
            return result.value;
        }

        /**
         * 遍历字段/变量
         * @param consumer Consumer<VariableWrapper>
         */
        void forEach(Consumer<VariableWrapper> consumer) {
            variableList.forEach(consumer);
        }

        /**
         * 获取类名
         * @return 类名
         */
        String getSimpleName() {
            return classDecl.getSimpleName().toString();
        }

        /**
         * 构建javaFile
         * @return JavaFile
         */
        JavaFile build() {
            PackageElement packageEle = elements.getPackageOf(element);
            return JavaFile.builder(packageEle.toString(), classSpec.build())
                    .addFileComment("This file is generated by program. Do not edit it manually")
                    .indent("    ")
                    .build();
        }

        /**
         * 写入到文件中
         */
        void writeToFile(String outPath) {
            try {
                JavaFile javaFile = build();
                FileObject resource = filer.getResource(StandardLocation.SOURCE_OUTPUT, javaFile.packageName, className);
                String path = Utils.fixPath(resource.toUri().getPath());
                path = path.substring(0, path.indexOf(TARGET_PATH)) + outPath;
                javaFile.writeTo(new File(path));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /**
         * 写入到文件中
         */
        void writeToFile() {
            try {
                JavaFile javaFile = build();
                javaFile.writeTo(filer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /**
         * 是否自定义序列化，只要复写了readFrom或writeTo中的一个就算，严格要求两个都复写
         * @return 是 true
         */
        private boolean isCustomizedSerialize() {
            boolean customizedRead = hasMethodDecl(SerializerBound.READ.accessName, SerializerBound.READ.stream);
            boolean customizedWrite = hasMethodDecl(SerializerBound.WRITE.accessName, SerializerBound.WRITE.stream);
            // 复写了readFrom或writeTo
            if (customizedRead || customizedWrite) {
                // 加个校验
                if (customizedRead != customizedWrite) {
                    printError(classDecl.getSimpleName().toString(), "未同时复写readFrom和writeTo，请检查!");
                }
                return true;
            }
            return false;
        }
    }

    /** 变量字段包装 */
    private static class VariableWrapper {
        /** 字段元素 */
        Element element;
        /** 字段定义 */
        JCTree.JCVariableDecl variable;

        VariableWrapper(Element element, JCTree.JCVariableDecl variable) {
            this.element = element;
            this.variable = variable;
        }
    }

    /**
     * 用于闭包的result
     */
    private static class Result<T> {
        /** 结果的值 */
        private T value;

        Result(T value) {
            this.value = value;
        }
    }
}
