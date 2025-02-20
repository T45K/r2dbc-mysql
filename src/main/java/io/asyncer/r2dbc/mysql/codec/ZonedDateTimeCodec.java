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
import io.asyncer.r2dbc.mysql.constant.MySqlType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.jetbrains.annotations.Nullable;
import reactor.core.publisher.Mono;

import java.lang.reflect.ParameterizedType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoZonedDateTime;

/**
 * Codec for {@link ZonedDateTime} and {@link ChronoZonedDateTime}.
 * <p>
 * For now, supports only A.D. calendar in {@link ChronoZonedDateTime}.
 */
final class ZonedDateTimeCodec implements ParametrizedCodec<ZonedDateTime> {

    static final ZonedDateTimeCodec INSTANCE = new ZonedDateTimeCodec();

    private ZonedDateTimeCodec() {
    }

    @Override
    public ZonedDateTime decode(ByteBuf value, MySqlColumnMetadata metadata, Class<?> target, boolean binary,
        CodecContext context) {
        return decode0(value, binary, context);
    }

    @Override
    public ChronoZonedDateTime<LocalDate> decode(ByteBuf value, MySqlColumnMetadata metadata,
        ParameterizedType target, boolean binary, CodecContext context) {
        return decode0(value, binary, context);
    }

    @Override
    public MySqlParameter encode(Object value, CodecContext context) {
        return new ZonedDateTimeMySqlParameter((ZonedDateTime) value, context);
    }

    @Override
    public boolean canEncode(Object value) {
        return value instanceof ZonedDateTime;
    }

    @Override
    public boolean canDecode(MySqlColumnMetadata metadata, ParameterizedType target) {
        return DateTimes.canDecodeChronology(metadata.getType(), target, ChronoZonedDateTime.class);
    }

    @Override
    public boolean canDecode(MySqlColumnMetadata metadata, Class<?> target) {
        return DateTimes.canDecodeDateTime(metadata.getType(), target, ZonedDateTime.class);
    }

    @Nullable
    private static ZonedDateTime decode0(ByteBuf value, boolean binary, CodecContext context) {
        LocalDateTime origin = LocalDateTimeCodec.decodeOrigin(value, binary, context);
        return origin == null ? null : ZonedDateTime.of(origin, context.getServerZoneId());
    }

    private static final class ZonedDateTimeMySqlParameter extends AbstractMySqlParameter {

        private final ZonedDateTime value;

        private final CodecContext context;

        private ZonedDateTimeMySqlParameter(ZonedDateTime value, CodecContext context) {
            this.value = value;
            this.context = context;
        }

        @Override
        public Mono<ByteBuf> publishBinary(final ByteBufAllocator allocator) {
            return Mono.fromSupplier(() -> LocalDateTimeCodec.encodeBinary(allocator, serverValue()));
        }

        @Override
        public Mono<Void> publishText(ParameterWriter writer) {
            return Mono.fromRunnable(() -> LocalDateTimeCodec.encodeText(writer, serverValue()));
        }

        @Override
        public MySqlType getType() {
            return MySqlType.TIMESTAMP;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ZonedDateTimeMySqlParameter)) {
                return false;
            }

            ZonedDateTimeMySqlParameter that = (ZonedDateTimeMySqlParameter) o;

            return value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

        private LocalDateTime serverValue() {
            return value.withZoneSameInstant(context.getServerZoneId())
                .toLocalDateTime();
        }
    }
}
