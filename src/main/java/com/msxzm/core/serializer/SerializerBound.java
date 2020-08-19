package com.msxzm.core.serializer;

import com.msxzm.base.stream.InputStream;
import com.msxzm.base.stream.OutputStream;

/**
 * 序列化方向
 * @author zenghongming
 * @date 2020/8/18 15:57
 */
public enum SerializerBound {
    /** read */
    READ("readFrom", "doRead", "inputStream", InputStream.class),
    /** write */
    WRITE("writeTo", "doWrite", "outputStream", OutputStream.class);

    /** 方法名 */
    String accessName;
    /** 序列化方法名 */
    String serializerExec;
    /** 参数名 */
    String paramName;
    /** 操作流 */
    Class<?> stream;

    SerializerBound(String accessName, String serializerExec, String paramName, Class<?> streamClass) {
        this.accessName = accessName;
        this.serializerExec = serializerExec;
        this.paramName = paramName;
        this.stream = streamClass;
    }

    /**
     * 操作流完全限定名
     * @return 操作流完全限定名
     */
    String getStreamClass() {
        return stream.getTypeName();
    }
}
