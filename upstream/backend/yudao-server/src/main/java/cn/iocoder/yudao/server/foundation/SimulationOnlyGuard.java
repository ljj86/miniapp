package cn.iocoder.yudao.server.foundation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Foundation-only guard. This is not a payment or lending implementation.
 * Startup fails closed unless the explicit isolated simulation configuration is supplied.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class SimulationOnlyGuard extends OncePerRequestFilter {
    private static final String[] BLOCKED_PREFIXES = {
        "/admin-api/pay", "/app-api/pay", "/api/v1/real-payments",
        "/api/v1/withdrawals", "/api/v1/settlements", "/api/v1/deposits",
        "/api/v1/credit", "/api/v1/api-packages"
    };

    public SimulationOnlyGuard(
            @Value("${simulation.environment:UNCONFIGURED}") String environment,
            @Value("${simulation.real-funds-enabled:true}") boolean realFundsEnabled) {
        if (!"SIMULATION".equals(environment) || realFundsEnabled) {
            throw new IllegalStateException("This foundation build requires SIMULATION and real-funds-enabled=false");
        }
    }

    static boolean isBlocked(String path) {
        for (String prefix : BLOCKED_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String decoded;
        try {
            decoded = UriUtils.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalidEncoding) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        if (isBlocked(path) || isBlocked(decoded)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"msg\":\"SIMULATION_ONLY: real-money capability is disabled\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }
}
