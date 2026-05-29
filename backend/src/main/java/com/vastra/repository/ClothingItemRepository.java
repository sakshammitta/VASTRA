package com.vastra.repository;

import com.vastra.entity.ClothingCategory;
import com.vastra.entity.ClothingItemEntity;
import com.vastra.entity.OwnershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ClothingItemRepository extends JpaRepository<ClothingItemEntity, UUID> {
    List<ClothingItemEntity> findByOwnerIdOrderByAddedAtDesc(UUID ownerId);
    List<ClothingItemEntity> findByOwnerIdAndOwnershipStatusOrderByAddedAtDesc(UUID ownerId, OwnershipStatus status);
    List<ClothingItemEntity> findByOwnerIdAndCategoryAndOwnershipStatus(UUID ownerId, ClothingCategory category, OwnershipStatus status);

    @Query(nativeQuery = true, value = """
        SELECT * FROM clothing_items
        WHERE owner_id != CAST(:userId AS uuid)
          AND fashion_clip_embedding IS NOT NULL
        ORDER BY fashion_clip_embedding <=> CAST(:embedding AS vector)
        LIMIT :limit
    """)
    List<ClothingItemEntity> findRecommendedByStyleEmbedding(
        @Param("userId") String userId,
        @Param("embedding") String embedding,
        @Param("limit") int limit
    );

    @Query(nativeQuery = true, value = """
        SELECT * FROM clothing_items
        WHERE owner_id = CAST(:userId AS uuid)
          AND fashion_clip_embedding IS NOT NULL
        ORDER BY fashion_clip_embedding <=> CAST(:postEmbedding AS vector)
        LIMIT :limit
    """)
    List<ClothingItemEntity> findWardrobeMatchesForPost(
        @Param("userId") String userId,
        @Param("postEmbedding") String postEmbedding,
        @Param("limit") int limit
    );

    @Query(nativeQuery = true, value = """
        SELECT * FROM clothing_items
        WHERE owner_id != CAST(:userId AS uuid)
          AND fashion_clip_embedding IS NOT NULL
        ORDER BY fashion_clip_embedding <=> CAST(:postEmbedding AS vector)
        LIMIT :limit
    """)
    List<ClothingItemEntity> findGapItemsForPost(
        @Param("userId") String userId,
        @Param("postEmbedding") String postEmbedding,
        @Param("limit") int limit
    );
}
