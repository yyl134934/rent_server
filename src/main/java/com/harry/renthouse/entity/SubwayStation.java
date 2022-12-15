package com.harry.renthouse.entity;

import lombok.Data;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

/**
 * 地铁站实体类
 * @author admin
 * @date 2020/5/9 10:04
 */
@Entity
@Data
public class SubwayStation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long subwayId;

    private String name;
}
