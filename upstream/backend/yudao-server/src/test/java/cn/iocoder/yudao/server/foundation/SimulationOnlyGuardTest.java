package cn.iocoder.yudao.server.foundation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class SimulationOnlyGuardTest {
    @Test void explicitSimulationStarts() {
        assertDoesNotThrow(() -> new SimulationOnlyGuard("SIMULATION", false));
    }
    @Test void missingOrRealConfigurationFailsClosed() {
        assertThrows(IllegalStateException.class, () -> new SimulationOnlyGuard("UNCONFIGURED", false));
        assertThrows(IllegalStateException.class, () -> new SimulationOnlyGuard("PRODUCTION", false));
        assertThrows(IllegalStateException.class, () -> new SimulationOnlyGuard("SIMULATION", true));
    }
    @Test void realMoneyRoutesAreBlockedBeforeBusinessHandlers() throws Exception {
        for (String path : new String[]{"/admin-api/pay/order/create", "/app-api/pay/order/submit",
                "/api/v1/withdrawals", "/api/v1/settlements/create", "/api/v1/deposits",
                "/api/v1/credit/inquiries", "/api/v1/real-payments", "/admin-api/%70ay/order/create"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            new SimulationOnlyGuard("SIMULATION", false).doFilter(request, response, chain);
            assertEquals(403, response.getStatus(), path);
            assertNull(chain.getRequest(), path);
            assertTrue(response.getContentAsString().contains("SIMULATION_ONLY"), path);
        }
    }
    @Test void ordinaryAuthAndFutureMockRoutesContinueToNormalAuthorization() throws Exception {
        for (String path : new String[]{"/admin-api/system/auth/login", "/actuator/health", "/api/v1/repayments"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockFilterChain chain = new MockFilterChain();
            new SimulationOnlyGuard("SIMULATION", false).doFilter(request, new MockHttpServletResponse(), chain);
            assertSame(request, chain.getRequest(), path);
        }
    }
}
