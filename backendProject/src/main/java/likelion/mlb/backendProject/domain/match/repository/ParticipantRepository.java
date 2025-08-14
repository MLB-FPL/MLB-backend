package likelion.mlb.backendProject.domain.match.repository;


import likelion.mlb.backendProject.domain.match.dto.AssignDto;

import likelion.mlb.backendProject.domain.draft.entity.Draft;
import likelion.mlb.backendProject.domain.match.entity.Participant;
import likelion.mlb.backendProject.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

//FIXME 주석 추가할 것
public interface ParticipantRepository extends JpaRepository<Participant, UUID> {
  boolean existsByDraft_IdAndUser_Id(UUID draftId, UUID userId);


    @Query("""
       select new likelion.mlb.backendProject.domain.match.dto.AssignDto(p.draft.id, p.userNumber)
       from Participant p
       where p.dummy = false
         and p.user.id = :userId
         and p.draft.isDeleted = false
         and p.draft.round.id = :roundId
    """)
    AssignDto findAssignment(@Param("userId") UUID userId,
                             @Param("roundId") UUID roundId);

  Optional<Participant> findByUserAndDraft(User user, Draft draft);
}
