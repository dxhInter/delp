package com.hmdp.controller;


import cn.hutool.core.collection.CollectionUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
@Slf4j
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @GetMapping("list")
    public Result queryTypeList() {
        List<ShopType> typeList = typeService.queryList();
        if (CollectionUtil.isEmpty(typeList)) {
            log.error("暂无店铺数据, 可能发生了缓存穿透");
            return Result.fail("暂无店铺数据");
        }
        return Result.ok(typeList);
    }
}
