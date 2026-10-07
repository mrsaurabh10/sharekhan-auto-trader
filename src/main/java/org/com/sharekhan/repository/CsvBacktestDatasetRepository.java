package org.com.sharekhan.repository;
import org.com.sharekhan.entity.CsvBacktestDatasetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface CsvBacktestDatasetRepository extends JpaRepository<CsvBacktestDatasetEntity,String> {
    List<CsvBacktestDatasetEntity> findAllByOrderByImportedAtDesc();
}
