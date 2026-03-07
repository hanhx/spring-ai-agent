package com.hhx.agi.infra.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.*;

/**
 * MyBatis TypeHandler：处理 MySQL 8.4 VECTOR 类型
 * 将 Java float[] 与 MySQL VECTOR(n) 互相转换
 */
@MappedTypes(float[].class)
public class VectorTypeHandler extends BaseTypeHandler<float[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, float[] parameter, JdbcType jdbcType) throws SQLException {
        // float[] -> byte[]
        byte[] bytes = floatArrayToBytes(parameter);
        ps.setBytes(i, bytes);
    }

    @Override
    public float[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        byte[] bytes = rs.getBytes(columnName);
        return bytesToFloatArray(bytes);
    }

    @Override
    public float[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        byte[] bytes = rs.getBytes(columnIndex);
        return bytesToFloatArray(bytes);
    }

    @Override
    public float[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        byte[] bytes = cs.getBytes(columnIndex);
        return bytesToFloatArray(bytes);
    }

    /**
     * float[] 转 byte[]（MySQL VECTOR 格式）
     */
    private byte[] floatArrayToBytes(float[] floats) {
        if (floats == null || floats.length == 0) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN); // MySQL VECTOR 使用小端序
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    /**
     * byte[] 转 float[]
     */
    private float[] bytesToFloatArray(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        int floatCount = bytes.length / 4;
        float[] floats = new float[floatCount];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < floatCount; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }
}