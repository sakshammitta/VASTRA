package com.vastra.repository;

import com.vastra.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByEmail(String email);
    Optional<UserEntity> findByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);

    @Query(nativeQuery = true, value = """
        SELECT * FROM users
        WHERE style_embedding IS NOT NULL
        ORDER BY style_embedding <=> CAST(:embedding AS vector)
        LIMIT :limit
    """)
    List<UserEntity> findSimilarByStyleEmbedding(@Param("embedding") String embedding, @Param("limit") int limit);
}
