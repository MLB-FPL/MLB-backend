package likelion.mlb.backendProject.domain.match.ws.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import likelion.mlb.backendProject.domain.match.dto.RoundInfo;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 서버 → 클라이언트로 브로드캐스트되는 매치 상태 메시지.
 * - remainingTime 은 표시 편의용(HH:MM:SS)
 * - serverTime 은 드리프트 보정용(선택)
 */
@Schema(
        description = "매치 상태(WebSocket) 메시지",
        example = """
    {
      "type": "STATUS",
      "count": 12,
      "remainingTime": "00:42:10",
      "state": "OPEN",
      "round": {
        "id": "11111111-1111-1111-1111-111111111111",
        "no": 3,
        "openAt": "2025-08-14T08:00:00",
        "lockAt": "2025-08-14T14:35:00"
      },
      "serverTime": "2025-08-14T12:10:50"
    }
    """
)
@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StatusMessage {

    @Schema(description = "메시지 타입", example = "STATUS", allowableValues = { "STATUS" })
    private final String type = "STATUS";

    @Schema(description = "대기열 인원 수", example = "12", minimum = "0")
    private final long count;

    @Schema(description = "남은 시간(HH:MM:SS)", example = "00:42:10")
    private final String remainingTime;

    @Schema(
            description = "상태 값",
            example = "OPEN",
            allowableValues = { "BEFORE_OPEN", "OPEN", "LOCKED", "LOCKED_HOLD" }
    )
    private final String state;

    @Schema(description = "라운드 정보(오픈/락 절대시각 포함)")
    private final RoundInfo round;

    @Schema(description = "서버 기준 현재 시각(ISO_LOCAL_DATE_TIME, 선택)", example = "2025-08-14T12:10:50")
    private final String serverTime; // 선택
}
