package com.harry.renthouse.repository;

import com.harry.renthouse.entity.House;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 *  房屋dao
 * @author admin
 * @date 2020/5/9 14:21
 */
public interface HouseRepository  extends JpaRepository<House, Long>, JpaSpecificationExecutor<House> {

    Optional<House> findByIdAndAdminId(Long id, Long adminId);

    @Modifying
    @Query("update House as house set house.cover = :cover where house.id = :id")
    void updateCover(Long id, String cover);

    @Modifying
    @Query("update House as house set house.status = :status where house.id = :id")
    void updateStatus(Long id, int status);
}
