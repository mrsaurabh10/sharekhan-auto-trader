package org.com.sharekhan.repository;

import org.com.sharekhan.entity.EntryChaseControl;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EntryChaseControlRepository extends JpaRepository<EntryChaseControl, Long> {
    List<EntryChaseControl> findByStateNot(EntryChaseControl.State state);
}
