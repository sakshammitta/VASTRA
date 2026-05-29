package com.vastra.repository;

import com.vastra.entity.PostEntity;
import com.vastra.entity.PostVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PostRepository extends JpaRepository<PostEntity, UUID> {
    Page<PostEntity> findByAuthorIdOrderByCreatedAtDesc(UUID authorId, Pageable pageable);

    @Query("""
        SELECT p FROM PostEntity p
        WHERE p.visibility = com.vastra.entity.PostVisibility.PUBLIC
           OR (p.visibility = com.vastra.entity.PostVisibility.FRIENDS_ONLY AND p.author IN
               (SELECT u FROM UserEntity u JOIN u.friends f WHERE f.id = :userId))
        ORDER BY p.createdAt DESC
    """)
    Page<PostEntity> findFeedForUser(@Param("userId") UUID userId, Pageable pageable);
}
