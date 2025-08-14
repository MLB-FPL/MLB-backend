package likelion.mlb.backendProject.global.configuration;

import likelion.mlb.backendProject.domain.match.ws.MatchHandler;
import likelion.mlb.backendProject.global.security.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 매칭(WebSocket) 엔드포인트 설정.
 *
 * - 경로: "/ws/match"
 * - 인증: 핸드셰이크 단계에서 JWT 검증(AuthHandshakeHandler)
 * - CORS(Origin): 프론트엔드 도메인만 허용 (환경별로 프로퍼티/ENV로 주입)
 *
 * ⚠️ 운영에서 여러 도메인을 허용해야 하면 setAllowedOriginPatterns(...) 사용 고려.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class MatchWebSocketConfig implements WebSocketConfigurer {

    /** 메시지 I/O 담당 핸들러 (비즈니스 로직은 서비스 계층으로 위임 권장) */
    private final MatchHandler matchHandler;

    /** JWT 파싱/검증 제공 (핸드셰이크 단계에서 토큰 검증에 사용) */
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 허용할 프론트엔드 Origin.
     * 우선순위: application.properties(frontend.http.url) → ENV(FRONTEND_HTTP_URL) → 기본값(localhost:5173)
     * 예) 운영 배포 시 FRONTEND_HTTP_URL=https://app.example.com
     */
    @Value("${frontend.http.url:${FRONTEND_HTTP_URL:http://localhost:5173}}")
    private String frontendHttpUrl;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(matchHandler, "/ws/match")                // 1) 엔드포인트 등록
                .setHandshakeHandler(new AuthHandshakeHandler(jwtTokenProvider)) // 2) 핸드셰이크 단계 JWT 검증
                .setAllowedOrigins(frontendHttpUrl);                   // 3) CORS: 지정 Origin만 허용
        // .setAllowedOriginPatterns("https://*.example.com"); // (선택) 와일드카드 패턴 허용 예시
    }
}
