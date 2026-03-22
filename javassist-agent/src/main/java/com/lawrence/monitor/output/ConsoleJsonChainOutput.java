package com.lawrence.monitor.output;

/**
 * 将调用链以 JSON 格式打印到控制台。
 * 如需对接 MQ / ELK，继承 {@link JsonChainOutput} 并实现 {@link #send} 即可。
 */
public class ConsoleJsonChainOutput extends JsonChainOutput {

    @Override
    protected void send(String json) {
        System.out.println(json);
    }
}
