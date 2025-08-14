package likelion.mlb.backendProject.domain.match.ws.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 서버 → 클라이언트로 1회 발송되는 사용자 식별(WebSocket) 메시지.
 */
@Schema(
        description = "사용자 식별(WebSocket) 메시지",
        example = """
    {
      "type": "USER_ID",
      "userId": "969a6b7d-2a24-41ca-9f46-d1d2f8012844"
    }
    """
)
@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserIdMessage {

    @Schema(description = "메시지 타입", example = "USER_ID", allowableValues = { "USER_ID" })
    private final String type = "USER_ID";

    @Schema(description = "서버가 판단한 로그인 사용자 ID(일반적으로 UUID 문자열)", example = "969a6b7d-2a24-41ca-9f46-d1d2f8012844")
    @NotBlank
    private final String userId;
}
