/**
 * Copyright 2016-2017 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.http2.internal;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.reaktivity.nukleus.buffer.BufferPool;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.function.MessageFunction;
import org.reaktivity.nukleus.function.MessagePredicate;
import org.reaktivity.nukleus.http2.internal.types.control.RouteFW;
import org.reaktivity.nukleus.http2.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.http2.internal.types.stream.DataFW;
import org.reaktivity.nukleus.http2.internal.types.stream.EndFW;
import org.reaktivity.nukleus.http2.internal.types.stream.HpackHeaderBlockFW;
import org.reaktivity.nukleus.http2.internal.types.stream.Http2ContinuationFW;
import org.reaktivity.nukleus.http2.internal.types.stream.Http2DataExFW;
import org.reaktivity.nukleus.http2.internal.types.stream.Http2DataFW;
import org.reaktivity.nukleus.http2.internal.types.stream.Http2FrameFW;
import org.reaktivity.nukleus.http2.internal.types.stream.Http2HeadersFW;
import org.reaktivity.nukleus.http2.internal.types.stream.Http2PingFW;
import org.reaktivity.nukleus.http2.internal.types.stream.Http2PrefaceFW;
import org.reaktivity.nukleus.http2.internal.types.stream.Http2PriorityFW;
import org.reaktivity.nukleus.http2.internal.types.stream.Http2SettingsFW;
import org.reaktivity.nukleus.http2.internal.types.stream.Http2WindowUpdateFW;
import org.reaktivity.nukleus.http2.internal.types.stream.HttpBeginExFW;
import org.reaktivity.nukleus.http2.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.http2.internal.types.stream.WindowFW;
import org.reaktivity.nukleus.route.RouteHandler;
import org.reaktivity.nukleus.stream.StreamFactory;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;
import static org.agrona.BitUtil.findNextPositivePowerOfTwo;
import static org.reaktivity.nukleus.http2.internal.Slab.NO_SLOT;

public final class ServerStreamFactory implements StreamFactory
{
    private static final double OUTWINDOW_LOW_THRESHOLD = 0.5;      // TODO configuration

    final MutableDirectBuffer read = new UnsafeBuffer(new byte[0]);
    final MutableDirectBuffer write = new UnsafeBuffer(new byte[0]);

    final RouteFW routeRO = new RouteFW();

    final BeginFW beginRO = new BeginFW();
    final DataFW dataRO = new DataFW();
    final EndFW endRO = new EndFW();

    final BeginFW.Builder beginRW = new BeginFW.Builder();
    final DataFW.Builder dataRW = new DataFW.Builder();
    final EndFW.Builder endRW = new EndFW.Builder();

    final WindowFW windowRO = new WindowFW();
    final ResetFW resetRO = new ResetFW();

    final Http2PrefaceFW prefaceRO = new Http2PrefaceFW();
    final Http2FrameFW http2RO = new Http2FrameFW();
    final Http2SettingsFW settingsRO = new Http2SettingsFW();
    final Http2DataFW http2DataRO = new Http2DataFW();
    final Http2HeadersFW headersRO = new Http2HeadersFW();
    final Http2ContinuationFW continationRO = new Http2ContinuationFW();
    final HpackHeaderBlockFW blockRO = new HpackHeaderBlockFW();
    final Http2WindowUpdateFW http2WindowRO = new Http2WindowUpdateFW();
    final Http2PriorityFW priorityRO = new Http2PriorityFW();
    final UnsafeBuffer scratch = new UnsafeBuffer(new byte[8192]);  // TODO
    final HttpBeginExFW.Builder httpBeginExRW = new HttpBeginExFW.Builder();
    final DirectBuffer nameRO = new UnsafeBuffer(new byte[0]);
    final DirectBuffer valueRO = new UnsafeBuffer(new byte[0]);
    final HttpBeginExFW beginExRO = new HttpBeginExFW();
    final Http2DataExFW dataExRO = new Http2DataExFW();

    final Http2PingFW pingRO = new Http2PingFW();

    final WindowFW.Builder windowRW = new WindowFW.Builder();
    final ResetFW.Builder resetRW = new ResetFW.Builder();

    private final Http2Configuration config;
    private final RouteHandler router;
    private final MutableDirectBuffer writeBuffer;
    private final BufferPool bufferPool;
    final LongSupplier supplyStreamId;
    final LongSupplier supplyCorrelationId;

    final Long2ObjectHashMap<Correlation> correlations;
    private final MessageFunction<RouteFW> wrapRoute;

    final Slab frameSlab;
    final Slab headersSlab;


    public ServerStreamFactory(
            Http2Configuration config,
            RouteHandler router,
            MutableDirectBuffer writeBuffer,
            Supplier<BufferPool> supplyBufferPool,
            LongSupplier supplyStreamId,
            LongSupplier supplyCorrelationId,
            Long2ObjectHashMap<Correlation> correlations)
    {
        this.config = config;
        this.router = requireNonNull(router);
        this.writeBuffer = requireNonNull(writeBuffer);
        this.bufferPool = requireNonNull(supplyBufferPool).get();
        this.supplyStreamId = requireNonNull(supplyStreamId);
        this.supplyCorrelationId = requireNonNull(supplyCorrelationId);
        this.correlations = requireNonNull(correlations);

        int slotCapacity = findNextPositivePowerOfTwo(Settings.DEFAULT_INITIAL_WINDOW_SIZE);
        int totalCapacity = findNextPositivePowerOfTwo(128) * slotCapacity;
        this.frameSlab = new Slab(totalCapacity, slotCapacity);
        this.headersSlab = new Slab(totalCapacity, slotCapacity);

        this.wrapRoute = this::wrapRoute;
    }

    @Override
    public MessageConsumer newStream(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length,
            MessageConsumer throttle)
    {
        final BeginFW begin = beginRO.wrap(buffer, index, index + length);
        final long sourceRef = begin.sourceRef();

        MessageConsumer newStream = null;

        if (sourceRef == 0L)
        {
            newStream = newConnectReplyStream(begin, throttle);
        }
        else
        {
            newStream = newAcceptStream(begin, throttle);
        }

        return newStream;
    }

    private MessageConsumer newAcceptStream(
            final BeginFW begin,
            final MessageConsumer networkThrottle)
    {
        final long networkRef = begin.sourceRef();
        final String acceptName = begin.source().asString();

        final MessagePredicate filter = (t, b, o, l) ->
        {
            final RouteFW route = routeRO.wrap(b, o, l);
            return networkRef == route.sourceRef() &&
                    acceptName.equals(route.source().asString());
        };

        final RouteFW route = router.resolve(filter, this::wrapRoute);

        MessageConsumer newStream = null;

        if (route != null)
        {
            final long networkId = begin.streamId();


            newStream = new ServerAcceptStream(networkThrottle, networkId, networkRef)::handleStream;
        }

        return newStream;
    }

    private MessageConsumer newConnectReplyStream(
            final BeginFW begin,
            final MessageConsumer throttle)
    {
        final long throttleId = begin.streamId();

        return new ServerConnectReplyStream(throttle, throttleId)::handleStream;
    }

    private RouteFW wrapRoute(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
    {
        return routeRO.wrap(buffer, index, index + length);
    }

    private final class ServerAcceptStream
    {
        private final MessageConsumer networkThrottle;
        private final long networkId;
        private final long networkRef;

        private String networkReplyName;
        private MessageConsumer networkReply;
        private long networkReplyId;

        private int networkSlot = NO_SLOT;
        private int networkSlotOffset;

        private MessageConsumer applicationTarget;
        private long applicationId;

        private MessageConsumer streamState;
        private Http2Connection http2Connection;
        private int window;
        private int outWindow;
        private int outWindowThreshold = -1;

        private ServerAcceptStream(
                MessageConsumer networkThrottle,
                long networkId,
                long networkRef)
        {
            this.networkThrottle = networkThrottle;
            this.networkId = networkId;
            this.networkRef = networkRef;
            this.streamState = this::beforeBegin;
        }

        private void handleStream(
                int msgTypeId,
                DirectBuffer buffer,
                int index,
                int length)
        {
            streamState.accept(msgTypeId, buffer, index, length);
        }

        private void beforeBegin(
                int msgTypeId,
                DirectBuffer buffer,
                int index,
                int length)
        {
            if (msgTypeId == BeginFW.TYPE_ID)
            {
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                handleBegin(begin);
            }
            else
            {
                doReset(networkThrottle, networkId);
            }
        }

        private void afterBegin(
                int msgTypeId,
                DirectBuffer buffer,
                int index,
                int length)
        {
            switch (msgTypeId)
            {
                case DataFW.TYPE_ID:
                    final DataFW data = dataRO.wrap(buffer, index, index + length);
                    handleData(data);
                    break;
                case EndFW.TYPE_ID:
                    final EndFW end = endRO.wrap(buffer, index, index + length);
                    handleEnd(end);
                    break;
                default:
                    doReset(networkThrottle, networkId);
                    break;
            }
        }

        private void handleBegin(
                BeginFW begin)
        {
            final String networkReplyName = begin.source().asString();
            final long networkCorrelationId = begin.correlationId();

            final MessageConsumer networkReply = router.supplyTarget(networkReplyName);
            final long newNetworkReplyId = supplyStreamId.getAsLong();

            window = config.http2Window();
            doWindow(networkThrottle, networkId, window, 1);

            doBegin(networkReply, newNetworkReplyId, 0L, networkCorrelationId);
            router.setThrottle(networkReplyName, newNetworkReplyId, this::handleThrottle);

            this.streamState = this::afterBegin;
            this.networkReplyName = networkReplyName;
            this.networkReply = networkReply;
            this.networkReplyId = newNetworkReplyId;
            http2Connection = new Http2Connection(ServerStreamFactory.this, router, newNetworkReplyId,
                    networkReply, writeBuffer, wrapRoute);
            http2Connection.handleBegin(begin);
        }

        private void handleData(
                DataFW data)
        {
            window -= dataRO.length();
            if (window < 0)
            {
                doReset(networkThrottle, networkId);
                //http2Connection.handleReset();
            }
            else
            {
                this.window += dataRO.length();
                assert window <= config.http2Window();
                doWindow(networkThrottle, networkId, dataRO.length(), 1);

                http2Connection.handleData(data);
            }
        }

        private void handleEnd(
                EndFW end)
        {
            http2Connection.handleEnd(end);
        }

        private void handleThrottle(
                int msgTypeId,
                DirectBuffer buffer,
                int index,
                int length)
        {
            switch (msgTypeId)
            {
                case WindowFW.TYPE_ID:
                    final WindowFW window = windowRO.wrap(buffer, index, index + length);
                    handleWindow(window);
                    break;
                case ResetFW.TYPE_ID:
                    final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                    handleReset(reset);
                    break;
                default:
                    // ignore
                    break;
            }
        }

        private void handleWindow(
                WindowFW window)
        {
            int update = windowRO.update();
            if (outWindowThreshold == -1)
            {
                outWindowThreshold = (int) (OUTWINDOW_LOW_THRESHOLD * update);
            }
            outWindow += update;

            if (http2Connection != null)
            {
                // TODO remove the following and use SeverAcceptStream.outWindow
                http2Connection.outWindowThreshold = outWindowThreshold;
                http2Connection.outWindow = outWindow;

                http2Connection.handleWindow(window);
            }
        }

        private void handleReset(
                ResetFW reset)
        {
            http2Connection.handleReset(reset);

            doReset(networkThrottle, networkId);
        }
    }


    private final class ServerConnectReplyStream
    {
        private final MessageConsumer applicationReplyThrottle;
        private final long applicationReplyId;

        private MessageConsumer streamState;
        private int window;

        private Http2Connection http2Connection;
        private Correlation correlation;

        private ServerConnectReplyStream(
                MessageConsumer applicationReplyThrottle,
                long applicationReplyId)
        {
            this.applicationReplyThrottle = applicationReplyThrottle;
            this.applicationReplyId = applicationReplyId;
            this.streamState = this::beforeBegin;
        }

        private void handleStream(
                int msgTypeId,
                DirectBuffer buffer,
                int index,
                int length)
        {
            streamState.accept(msgTypeId, buffer, index, length);
        }

        private void beforeBegin(
                int msgTypeId,
                DirectBuffer buffer,
                int index,
                int length)
        {
            if (msgTypeId == BeginFW.TYPE_ID)
            {
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                handleBegin(begin);
            }
            else
            {
                doReset(applicationReplyThrottle, applicationReplyId);
            }
        }

        private void afterBegin(
                int msgTypeId,
                DirectBuffer buffer,
                int index,
                int length)
        {
            switch (msgTypeId)
            {
                case DataFW.TYPE_ID:
                    final DataFW data = dataRO.wrap(buffer, index, index + length);
                    handleData(data);
                    break;
                case EndFW.TYPE_ID:
                    final EndFW end = endRO.wrap(buffer, index, index + length);
                    handleEnd(end);
                    break;
                default:
                    doReset(applicationReplyThrottle, applicationReplyId);
                    break;
            }
        }

        private void handleBegin(
                BeginFW begin)
        {
            final long sourceRef = begin.sourceRef();
            final long correlationId = begin.correlationId();
            correlation = sourceRef == 0L ? correlations.remove(correlationId) : null;
            if (correlation != null)
            {
                http2Connection = correlation.http2Connection;

                window = config.httpWindow();
                doWindow(applicationReplyThrottle, applicationReplyId, window, 5);
                http2Connection.handleHttpBegin(begin, correlation);

                this.streamState = this::afterBegin;
            }
            else
            {
                doReset(applicationReplyThrottle, applicationReplyId);
            }
        }

        private void handleData(
                DataFW data)
        {
            window -= data.length();

            http2Connection.handleHttpData(data, correlation, this::sendWindow);
        }

        private void handleEnd(
                EndFW end)
        {
            http2Connection.handleHttpEnd(end, correlation);
        }

        private void sendWindow(int update)
        {
            window += update;
            doWindow(applicationReplyThrottle, applicationReplyId, window, 5);
        }

    }

    void doBegin(
            final MessageConsumer target,
            final long targetId,
            final long targetRef,
            final long correlationId)
    {
        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                                     .streamId(targetId)
                                     .source("http2")
                                     .sourceRef(targetRef)
                                     .correlationId(correlationId)
                                     .extension(e -> e.reset())
                                     .build();

        target.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());
    }

    void doWindow(
            final MessageConsumer throttle,
            final long throttleId,
            final int writableBytes,
            final int writableFrames)
    {
        final WindowFW window = windowRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                                        .streamId(throttleId)
                                        .update(writableBytes)
                                        .frames(writableFrames)
                                        .build();

        throttle.accept(window.typeId(), window.buffer(), window.offset(), window.sizeof());
    }

    void doReset(
            final MessageConsumer throttle,
            final long throttleId)
    {
        final ResetFW reset = resetRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                                     .streamId(throttleId)
                                     .build();

        throttle.accept(reset.typeId(), reset.buffer(), reset.offset(), reset.sizeof());
    }
}