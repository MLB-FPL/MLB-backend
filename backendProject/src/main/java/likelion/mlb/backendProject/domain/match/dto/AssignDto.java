package likelion.mlb.backendProject.domain.match.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Schema(description = "내 드래프트 배정(좌석) 정보")
@Getter
@AllArgsConstructor
public class AssignDto {

    @Schema(description = "드래프트 ID (UUID)", example = "11111111-1111-1111-1111-111111111111")
    @NotNull
    private UUID draftId;

    @Schema(description = "방 내 좌석 번호 (1~4)", example = "2", minimum = "1", maximum = "4")
    @Min(1) @Max(4)
    private short userNumber;
}

