package likelion.mlb.backendProject.domain.match.ws.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import likelion.mlb.backendProject.domain.match.dto.AssignDto;
import lombok.*;

import java.util.UUID;

/**
 * 드래프트 시작(WebSocket) 메시지.
 * 서버 → 클라이언트 단방향 송신 전용 DTO.
 */
@Schema(
        description = "드래프트 시작(WebSocket) 메시지",
        example = """
    {
      "type": "DRAFT_START",
      "draftId": "11111111-1111-1111-1111-111111111111",
      "userNumber": 3
    }
    """
)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DraftStartMessage {

    @Schema(description = "메시지 타입", example = "DRAFT_START", allowableValues = { "DRAFT_START" })
    @NotBlank
    @Builder.Default
    private String type = "DRAFT_START";   // 고정 값

    @Schema(description = "드래프트 ID (UUID)", example = "11111111-1111-1111-1111-111111111111")
    @NotNull
    private UUID draftId;

    @Schema(description = "방 내 좌석 번호 (1~4)", example = "3", minimum = "1", maximum = "4")
    @Min(1) @Max(4)
    private short userNumber;

    /** 편의 팩토리 */
    public static DraftStartMessage of(UUID draftId, short userNumber) {
        return DraftStartMessage.builder()
                .draftId(draftId)
                .userNumber(userNumber)
                .build(); // type은 Builder.Default로 자동 "DRAFT_START"
    }

    /** AssignDto → 메시지 변환 헬퍼 */
    public static DraftStartMessage fromAssign(String userId, AssignDto a) {
        return of(a.getDraftId(), a.getUserNumber());
    }
}
