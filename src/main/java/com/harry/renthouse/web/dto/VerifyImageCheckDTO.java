package com.harry.renthouse.web.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * @author admin
 * @date 2020/8/10 17:38
 */
@Data
public class VerifyImageCheckDTO {

    @ApiModelProperty(value = "完成百分比")
    private int verifyPercent;

    @ApiModelProperty(value = "校验码")
    private String verifyCode;
}
