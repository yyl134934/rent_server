package com.harry.renthouse.web.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 *  认证信息dto
 * @author admin
 * @date 2020/5/11 11:47
 */
@Data
@ApiModel("认证成功返回结果")
public class AuthenticationDTO{

    @ApiModelProperty(value = "登入成功后token凭证")
    private String token;

   /* @ApiModelProperty(value = "用户信息")
    private UserDTO user;*/
}
