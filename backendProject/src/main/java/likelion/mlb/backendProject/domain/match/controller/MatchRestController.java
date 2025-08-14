package likelion.mlb.backendProject.domain.match.controller;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import likelion.mlb.backendProject.domain.match.dto.MatchStatusResponse;
import likelion.mlb.backendProject.domain.match.service.MatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.*;

@Tag(name = "Match", description = "매칭 상태 조회")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/match")
public class MatchRestController {

    private final MatchService matchService;

    /**
     * 현재 매칭 상태 요약 조회.
     * - state: BEFORE_OPEN | OPEN | LOCKED
     * - remainingTime: "mm:ss" (LOCKED 시 "00:00")
     * - count: 현재 대기(세션) 사용자 수
     * - round: 라운드 식별자/번호/오픈·락 시각(KST 문자열, ISO_LOCAL_DATE_TIME)
     */
    @Operation(
            summary = "현재 매칭 상태 조회",
            description = "대기 인원수(count), 상태(state), 남은 시간(remainingTime, mm:ss), 라운드 정보(round)를 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "BEFORE_OPEN", value = """
                    {
                      "count": 27,
                      "state": "BEFORE_OPEN",
                      "remainingTime": "12:34",
                      "round": {
                        "id": "11111111-1111-1111-1111-111111111111",
                        "no": 3,
                        "openAt": "2025-08-18T20:50:00",
                        "lockAt": "2025-08-20T20:50:00"
                      }
                    }
                    """),
                                    @ExampleObject(name = "OPEN", value = """
                    {
                      "count": 31,
                      "state": "OPEN",
                      "remainingTime": "05:10",
                      "round": {
                        "id": "11111111-1111-1111-1111-111111111111",
                        "no": 3,
                        "openAt": "2025-08-18T20:50:00",
                        "lockAt": "2025-08-20T20:50:00"
                      }
                    }
                    """),
                                    @ExampleObject(name = "LOCKED", value = """
                    {
                      "count": 0,
                      "state": "LOCKED",
                      "remainingTime": "00:00",
                      "round": {
                        "id": "11111111-1111-1111-1111-111111111111",
                        "no": 3,
                        "openAt": "2025-08-18T20:50:00",
                        "lockAt": "2025-08-20T20:50:00"
                      }
                    }
                    """)
                            }
                    )
            )
    })
    @GetMapping("/status")
    public MatchStatusResponse getStatus() {
        return matchService.getCurrentStatus();
    }
}

