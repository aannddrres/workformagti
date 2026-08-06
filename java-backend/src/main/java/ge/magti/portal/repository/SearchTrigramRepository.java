package ge.magti.portal.repository;

import ge.magti.portal.domain.SearchTrigram;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SearchTrigramRepository extends JpaRepository<SearchTrigram, Long> {

    /**
     * Deliberately a bulk JPQL delete via {@code @Modifying}, not a derived
     * {@code deleteBy...} method -- Spring Data's derived deletes queue an
     * {@code entityManager.remove()} per matched row and defer it to flush
     * time, but the reindex insert that follows immediately in the same
     * transaction uses an IDENTITY-generated id, which Hibernate must send
     * to the database right away. Without {@code @Modifying}, the delete
     * hadn't actually reached Oracle yet when the insert ran, so the old
     * rows were still there and the unique constraint rejected the new
     * ones (found via a real ORA-00001 while testing this reindex path).
     */
    @Modifying
    @Query("DELETE FROM SearchTrigram st WHERE st.entityType = :entityType AND st.entityId = :entityId")
    void deleteByEntityTypeAndEntityId(@Param("entityType") String entityType, @Param("entityId") Long entityId);

    /**
     * Entity ids of {@code entityType} whose indexed trigram set is a
     * superset of {@code trigrams} -- a necessary but not sufficient
     * condition for "this entity's searchable text contains the word these
     * trigrams came from" (two different words can share a trigram set, or
     * share every individual trigram without containing it contiguously).
     * {@link ge.magti.portal.search.SearchQueryService} always re-verifies
     * every candidate with an exact substring check before treating it as a
     * real match; this query's only job is shrinking a full table scan down
     * to a small candidate list.
     */
    @Query("SELECT st.entityId FROM SearchTrigram st "
            + "WHERE st.entityType = :entityType AND st.trigram IN :trigrams "
            + "GROUP BY st.entityId "
            + "HAVING COUNT(DISTINCT st.trigram) = :trigramCount")
    List<Long> findCandidateEntityIds(@Param("entityType") String entityType,
            @Param("trigrams") Collection<String> trigrams, @Param("trigramCount") long trigramCount);
}
