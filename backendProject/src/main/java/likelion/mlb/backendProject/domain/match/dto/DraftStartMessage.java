package likelion.mlb.backendProject.domain.match.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.UUID;

@Schema(description = "드래프트 시작(WebSocket) 메시지")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DraftStartMessage {

    @Schema(description = "메시지 타입", example = "DRAFT_START", allowableValues = {"DRAFT_START"})
    @NotBlank
    private String type;       // "DRAFT_START"

    @Schema(description = "드래프트 ID (UUID)", example = "11111111-1111-1111-1111-111111111111")
    @NotNull
    private UUID draftId;

    @Schema(description = "방 내 좌석 번호 (1~4)", example = "3", minimum = "1", maximum = "4")
    @Min(1) @Max(4)
    private short userNumber;  // 1~4
}
