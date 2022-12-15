package com.harry.renthouse.service.house.impl;

import com.harry.renthouse.RentHouseApplicationTests;
import com.harry.renthouse.elastic.entity.BaiduMapLocation;
import com.harry.renthouse.service.ServiceMultiResult;
import com.harry.renthouse.service.house.AddressService;
import com.harry.renthouse.web.dto.SupportAddressDTO;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.Assert;

import javax.annotation.Resource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author admin
 * @date 2020/6/1 13:38
 */
class AddressServiceImplTest  extends RentHouseApplicationTests {


    @Resource
    private AddressService addressService;

    @Resource
    private RedisTemplate redisTemplate;

    @Test
    void getBaiduMapLocation() {
        String city = "杭州";
        String address = "杭州市萧山区钱江世纪城广孚联合国际中心";
        Optional<BaiduMapLocation> location = addressService.getBaiduMapLocation(city, address);
        Assert.isTrue(location.isPresent(), "获取位置为空");
        Assert.isTrue(location.get().getLon() > 0 , "获取纬度错误");
        Assert.isTrue(location.get().getLat() > 0 , "获取经度错误");
    }

    @Test
    void lbsUpload() {
        BaiduMapLocation baiduMapLocation = new BaiduMapLocation(120.252698, 30.247764);
        boolean result = addressService.lbsUpload(baiduMapLocation, "丽晶国际", "浙江省杭州市丽晶国际",
                12345L, 25, 66, "http://qa22ygxo8.bkt.clouddn.com/Fka2aHz-0iCj8g47xZVXWj5dF440");
        Assert.isTrue(result, "上传poi失败");
    }

    @Test
    void lbsRemove() {
        boolean result = addressService.lbsRemove(12345L);
        Assert.isTrue(result, "删除poi失败");
    }

    @Test
    void findAllCities() {
//        redisTemplate.opsForValue().set("aaa", addressService.findAllCities());
        ServiceMultiResult<SupportAddressDTO> result = (ServiceMultiResult<SupportAddressDTO>) redisTemplate.opsForValue().get("aaa");
        System.out.println(result);
        ServiceMultiResult<SupportAddressDTO> result2 = (ServiceMultiResult<SupportAddressDTO>) redisTemplate.opsForValue().get("bbb");
        System.out.println(result2);
        ServiceMultiResult<SupportAddressDTO> result3 = (ServiceMultiResult<SupportAddressDTO>) redisTemplate.opsForValue().get("support:cities::all");
        System.out.println(result3);
    }
}