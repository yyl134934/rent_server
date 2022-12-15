package com.harry.renthouse.web.form;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * @author admin
 * @date 2020/5/12 18:20
 */
@Data
@ApiModel("封面修改表单")
public class CoverForm {

    @NotNull(message = "封面id不能为空")
    @ApiModelProperty(value = "封面id(图片的id)")
    private Long coverId;

    @NotNull(message = "房屋id不能为空")
    @ApiModelProperty(value = "房屋id")
    private Long houseId;
}
