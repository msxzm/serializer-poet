package com.msxzm.core.serializer;

import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;

import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 部分工具方法
 * @author zenghongming
 * @date 2020/1/13 16:03
 */
class Utils {
    /** 基础类型组 */
    static final Map<String, Class> PRIMITIVE_MAP;
    /** 包装类型 -> 基础类型 */
    static final Map<String, Class> WRAPPER_PRIMITIVE_MAP;

    /** 数组在类名中的标识 */
    static final String CLASS_ARRAY_REGEX = "\\[]";
    /** 空字符串 */
    static final String EMPTY = "";

    static {
        PRIMITIVE_MAP = new HashMap<>();
        PRIMITIVE_MAP.put("boolean", boolean.class);
        PRIMITIVE_MAP.put("byte", byte.class);
        PRIMITIVE_MAP.put("short", short.class);
        PRIMITIVE_MAP.put("int", int.class);
        PRIMITIVE_MAP.put("float", float.class);
        PRIMITIVE_MAP.put("long", long.class);
        PRIMITIVE_MAP.put("double", double.class);
    }

    static {
        WRAPPER_PRIMITIVE_MAP = new HashMap<>();
        WRAPPER_PRIMITIVE_MAP.put("Boolean", boolean.class);
        WRAPPER_PRIMITIVE_MAP.put("Byte", byte.class);
        WRAPPER_PRIMITIVE_MAP.put("Short", short.class);
        WRAPPER_PRIMITIVE_MAP.put("Integer", int.class);
        WRAPPER_PRIMITIVE_MAP.put("Float", float.class);
        WRAPPER_PRIMITIVE_MAP.put("Long", long.class);
        WRAPPER_PRIMITIVE_MAP.put("Double", double.class);
        WRAPPER_PRIMITIVE_MAP.put("String", String.class);
    }

    /**
     * 判断两个方法是否一致
     * @param methodDecl1 方法1定义
     * @param methodDecl2 方法2定义
     * @return 方法签名是否一致
     */
    static boolean methodEquals(JCMethodDecl methodDecl1, JCMethodDecl methodDecl2) {
        String methodSignature1 = getMethodSignature(methodDecl1);
        String methodSignature2 = getMethodSignature(methodDecl2);
        return methodSignature1.equals(methodSignature2);
    }

    /**
     * 首字母大写
     * @param str 字符串
     * @return 首字母大写
     */
    static String toUpperCaseFirst(String str) {
        // 已经是大写了直接返回
        if (Character.isUpperCase(str.charAt(0))) {
            return str;
        }
        char[] chars = str.toCharArray();
        chars[0] = Character.toUpperCase(chars[0]);
        return String.valueOf(chars);
    }

    /**
     * 获取类型名
     * @param qualifiedName 类型完全限定名
     * @return 类型名
     */
    static String getTypeName(String qualifiedName) {
        // 取最后的类名称
        String[] arr = qualifiedName.split("\\.");
        if (arr.length < 1) {
            return null;
        }
        return arr[arr.length - 1];
    }

    /**
     * 擦除数组标识(多维数组递归擦除)
     * @param arrayType 数组类型
     * @return 擦除数组标识后的类型
     */
    static TypeMirror erasureArray(ArrayType arrayType) {
        if (arrayType.elemtype.getKind() == TypeKind.ARRAY) {
            return erasureArray((ArrayType) arrayType.elemtype);
        }
        return arrayType.elemtype;
    }

    /**
     * 获取方法签名
     * @return 方法签名
     */
    static String getMethodSignature(JCMethodDecl methodDecl) {
        StringBuilder builder = new StringBuilder(methodDecl.name);
        for (JCVariableDecl variableDecl : methodDecl.getParameters()) {
            builder.append("_");
            builder.append(getTypeName(variableDecl.vartype.toString()));
        }
        return builder.toString();
    }

    static String fixPath(String path) {
        return path.replace('\\', File.separatorChar).replace('/', File.separatorChar);
    }
}
