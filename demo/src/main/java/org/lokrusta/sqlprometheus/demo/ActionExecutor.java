package org.lokrusta.sqlprometheus.demo;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * Wraps a business call: logs the input and output, and records duration and result code.
 */
public class ActionExecutor {
    public static final String OPERATION_TIME = "operation_time";
    public static final String OPERATION_CODE = "operation_code";
    public static final String PROCESS_ACTION = "process_action";
    public static final String CODE = "code";

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final MeterRegistry meterRegistry;

    public ActionExecutor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public <P, R> R execute(String actionName, P input, Function<P, R> action) {
        log.info("Starting {} with {}", actionName, input);
        Timer.Sample sample = Timer.start(meterRegistry);
        String code = "SUCCESS";
        R result = null;
        try {
            result = action.apply(input);
            return result;
        } catch (RuntimeException e) {
            code = "ERROR";
            throw e;
        } finally {
            sample.stop(Timer.builder(OPERATION_TIME)
                    .tag(PROCESS_ACTION, actionName)
                    .tag(CODE, code)
                    .register(meterRegistry));
            meterRegistry.counter(OPERATION_CODE, PROCESS_ACTION, actionName, CODE, code).increment();
            log.info("Finished {} with {}", actionName, result);
        }
    }
}
