package likelion.mlb.backendProject.domain.match.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Schema(description = "매칭 상태 응답")
@Getter @AllArgsConstructor
public class MatchStatusResponse {
    @Schema(description = "현재 대기(세션) 사용자 수", example = "27")
    private long count;

    @Schema(description = "라운드 상태", allowableValues = {"BEFORE_OPEN","OPEN","LOCKED"}, example = "OPEN")
    private String state;

    @Schema(description = "다음 상태까지 남은 시간 (mm:ss). LOCKED 시 00:00", example = "05:10")
    private String remainingTime;

    @Schema(description = "라운드 요약 정보")
    private RoundInfo round;
}

