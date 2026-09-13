package com.agent.session.orchestration;

import com.agent.session.common.BizException;
import com.agent.session.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上游不可用时的降级行为：默认降级（意图→FALLBACK 闲聊、检索→空结果），
 * 显式关闭降级时返回 502 语义的 AGENT_UPSTREAM_UNAVAILABLE（并保留根因）。
 */
class UpstreamDegradeTest {

    @Test
    void nlpClientDegradesToFallbackIntent() throws Exception {
        NlpClient client = new NlpClient("http://127.0.0.1:" + closedPort(), true);
        Map<String, Object> result = client.recognize("你好", "session-1", "default");
        assertEquals("闲聊", result.get("intent"));
        assertEquals("FALLBACK", result.get("engine"));
    }

    @Test
    void nlpClientRaisesUpstreamUnavailableWhenDegradeDisabled() throws Exception {
        NlpClient client = new NlpClient("http://127.0.0.1:" + closedPort(), false);
        BizException error = assertThrows(BizException.class, () -> client.recognize("你好", "session-1", "default"));
        assertEquals(ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, error.errorCode());
        assertNotNull(error.getCause(), "根因应被保留，便于排障");
    }

    @Test
    void bodyClientDegradesToEmptyResult() throws Exception {
        BodyClient client = new BodyClient("http://127.0.0.1:" + closedPort(), true);
        List<Map<String, Object>> chunks = client.retrieve("架构是什么", "default");
        assertTrue(chunks.isEmpty());
    }

    @Test
    void bodyClientRaisesUpstreamUnavailableWhenDegradeDisabled() throws Exception {
        BodyClient client = new BodyClient("http://127.0.0.1:" + closedPort(), false);
        BizException error = assertThrows(BizException.class, () -> client.retrieve("架构是什么", "default"));
        assertEquals(ErrorCode.AGENT_UPSTREAM_UNAVAILABLE, error.errorCode());
    }

    /** 绑定后立即释放，得到一个当前无监听者的端口，用于制造「连接被拒绝」。 */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
