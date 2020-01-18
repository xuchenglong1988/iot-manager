package org.jetlinks.community.network.tcp.parser.strateies;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.parsetools.RecordParser;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.community.network.tcp.parser.PayloadParser;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * <pre>
 * PipePayloadParser parser = new PipePayloadParser();
 * parser.fixed(4)
 *       .handler(buffer -> {
 *            int len = BytesUtils.highBytes2Int(buffer.getBytes());
 *            parser.fixed(len);
 *         })
 *       .handler(buffer -> parser.result(buffer.toString("UTF-8")).complete());
 * </pre>
 */
@Slf4j
public class PipePayloadParser implements PayloadParser {

    private EmitterProcessor<Buffer> processor = EmitterProcessor.create(true);

    private List<Consumer<Buffer>> pipe = new CopyOnWriteArrayList<>();

    private List<Buffer> result = new CopyOnWriteArrayList<>();

    private volatile RecordParser recordParser;

    private Consumer<RecordParser> firstInit;

    private AtomicInteger currentPipe = new AtomicInteger();

    public PipePayloadParser result(String buffer) {
        return result(Buffer.buffer(buffer));
    }

    public PipePayloadParser result(byte[] buffer) {
        return result(Buffer.buffer(buffer));
    }

    public PipePayloadParser handler(Consumer<Buffer> handler) {
        pipe.add(handler);
        return this;
    }

    public PipePayloadParser delimited(String delimited) {
        if (recordParser == null) {
            setParser(RecordParser.newDelimited(delimited));
            firstInit = (parser -> parser.delimitedMode(delimited));
            return this;
        }
        recordParser.delimitedMode(delimited);
        return this;
    }

    public PipePayloadParser fixed(int size) {
        if (recordParser == null) {
            setParser(RecordParser.newFixed(size));
            firstInit = (parser -> parser.fixedSizeMode(size));
            return this;
        }
        recordParser.fixedSizeMode(size);
        return this;
    }

    private Consumer<Buffer> getNextHandler() {
        int i = currentPipe.getAndIncrement();
        if (i < pipe.size()) {
            return pipe.get(i);
        }
        currentPipe.set(0);
        return pipe.get(0);
    }

    private void setParser(RecordParser parser) {
        this.recordParser = parser;
        this.recordParser.handler(buffer -> getNextHandler().accept(buffer));
    }

    public PipePayloadParser complete() {
        currentPipe.set(0);
        firstInit.accept(recordParser);
//        if (!processor.hasDownstreams()) {
//            this.result.clear();
//            return this;
//        }
        if (!this.result.isEmpty()) {
            Buffer buffer = Buffer.buffer();
            for (Buffer buf : this.result) {
                buffer.appendBuffer(buf);
            }
            this.result.clear();
            processor.onNext(buffer);
        }
        return this;

    }

    public PipePayloadParser result(Buffer buffer) {
        this.result.add(buffer);
        return this;
    }

    @Override
    public synchronized void handle(Buffer buffer) {
        if (recordParser == null) {
            log.error("record parser not init");
            return;
        }
        recordParser.handle(buffer);
    }

    @Override
    public Flux<Buffer> handlePayload() {
        return processor.map(Function.identity());
    }

    @Override
    public void close() {
        processor.onComplete();
        currentPipe.set(0);
        this.result.clear();
    }

}