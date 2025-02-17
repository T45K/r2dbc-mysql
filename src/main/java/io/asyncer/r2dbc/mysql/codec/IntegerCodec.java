/*
 * Copyright 2023 asyncer.io projects
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.asyncer.r2dbc.mysql.codec;

import io.asyncer.r2dbc.mysql.MySqlColumnMetadata;
import io.asyncer.r2dbc.mysql.MySqlParameter;
import io.asyncer.r2dbc.mysql.ParameterWriter;
import io.asyncer.r2dbc.mysql.codec.ByteCodec.ByteMySqlParameter;
import io.asyncer.r2dbc.mysql.codec.ShortCodec.ShortMySqlParameter;
import io.asyncer.r2dbc.mysql.constant.MySqlType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Codec for {@code int}.
 */
final class IntegerCodec extends AbstractPrimitiveCodec<Integer> {

    static final IntegerCodec INSTANCE = new IntegerCodec();

    private IntegerCodec() {
        super(Integer.TYPE, Integer.class);
    }

    @Override
    public Integer decode(ByteBuf value, MySqlColumnMetadata metadata, Class<?> target, boolean binary,
        CodecContext context) {
        return decodeInt(value, binary, metadata.getType());
    }

    @Override
    public boolean canEncode(Object value) {
        return value instanceof Integer;
    }

    @Override
    public MySqlParameter encode(Object value, CodecContext context) {
        int v = (Integer) value;

        if ((byte) v == v) {
            return new ByteMySqlParameter((byte) v);
        } else if ((short) v == v) {
            return new ShortMySqlParameter((short) v);
        }

        return new IntMySqlParameter(v);
    }

    @Override
    public boolean canPrimitiveDecode(MySqlColumnMetadata metadata) {
        return metadata.getType().isNumeric();
    }

    static int decodeInt(ByteBuf buf, boolean binary, MySqlType type) {
        if (binary) {
            return decodeBinary(buf, type);
        }

        switch (type) {
            case FLOAT:
                return (int) Float.parseFloat(buf.toString(StandardCharsets.US_ASCII));
            case DOUBLE:
                return (int) Double.parseDouble(buf.toString(StandardCharsets.US_ASCII));
            case DECIMAL:
                return decimalInt(buf);
            default:
                return CodecUtils.parseInt(buf);
        }
    }

    private static int decodeBinary(ByteBuf buf, MySqlType type) {
        switch (type) {
            case BIGINT_UNSIGNED:
            case BIGINT:
            case INT_UNSIGNED:
            case INT:
            case MEDIUMINT_UNSIGNED:
            case MEDIUMINT:
                // MySQL was using little endian, only need lower 4-bytes for int64.
                // MySQL return 32-bits two's complement for 24-bits integer
                return buf.readIntLE();
            case SMALLINT_UNSIGNED:
                return buf.readUnsignedShortLE();
            case SMALLINT:
            case YEAR:
                return buf.readShortLE();
            case TINYINT_UNSIGNED:
                return buf.readUnsignedByte();
            case TINYINT:
                return buf.readByte();
            case DECIMAL:
                return decimalInt(buf);
            case FLOAT:
                return (int) buf.readFloatLE();
            case DOUBLE:
                return (int) buf.readDoubleLE();
        }

        throw new IllegalStateException("Cannot decode type " + type + " as an Integer");
    }

    private static int decimalInt(ByteBuf buf) {
        return new BigDecimal(buf.toString(StandardCharsets.US_ASCII)).intValue();
    }

    static final class IntMySqlParameter extends AbstractMySqlParameter {

        private final int value;

        IntMySqlParameter(int value) {
            this.value = value;
        }

        @Override
        public Mono<ByteBuf> publishBinary(final ByteBufAllocator allocator) {
            return Mono.fromSupplier(() -> allocator.buffer(Integer.BYTES).writeIntLE(value));
        }

        @Override
        public Mono<Void> publishText(ParameterWriter writer) {
            return Mono.fromRunnable(() -> writer.writeInt(value));
        }

        @Override
        public MySqlType getType() {
            return MySqlType.INT;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof IntMySqlParameter)) {
                return false;
            }

            IntMySqlParameter intValue = (IntMySqlParameter) o;

            return value == intValue.value;
        }

        @Override
        public int hashCode() {
            return value;
        }
    }
}
