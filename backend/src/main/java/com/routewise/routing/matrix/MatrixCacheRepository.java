package com.routewise.routing.matrix;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MatrixCacheRepository extends JpaRepository<MatrixCacheEntry, Long> {

    List<MatrixCacheEntry> findByNodeSetHash(String nodeSetHash);

    boolean existsByNodeSetHash(String nodeSetHash);
}
