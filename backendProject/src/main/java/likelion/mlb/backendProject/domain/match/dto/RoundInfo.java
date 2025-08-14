package likelion.mlb.backendProject.domain.match.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Getter @AllArgsConstructor
public class RoundInfo {
    @Schema(description = "라운드 ID (UUID)", example = "11111111-1111-1111-1111-111111111111")
    private UUID id;

    @Schema(description = "라운드 번호", example = "3")
    private Integer no;

    @Schema(description = "OPEN 시작 시각 (KST, ISO_LOCAL_DATE_TIME)", example = "2025-08-18T20:50:00")
    private String openAt;

    @Schema(description = "LOCK 시각 (KST, ISO_LOCAL_DATE_TIME)", example = "2025-08-20T20:50:00")
    private String lockAt;
}