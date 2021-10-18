package com.fitmgr.meterage.job;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitmgr.common.core.constant.enums.DeleteFlagStatusEnum;
import com.fitmgr.common.core.constant.enums.EnableStatusEnum;
import com.fitmgr.common.core.util.SpringContextHolder;
import com.fitmgr.job.api.core.biz.model.ReturnT;
import com.fitmgr.job.api.entity.Task;
import com.fitmgr.job.api.excutor.XxlBaseTaskExec;
import com.fitmgr.meterage.api.dto.ChargeItemDTO;
import com.fitmgr.meterage.api.entity.ChargeItem;
import com.fitmgr.meterage.api.entity.ResourceChargeRecord;
import com.fitmgr.meterage.constant.ChargeConstant;
import com.fitmgr.meterage.mapper.ChargeItemMapper;
import com.fitmgr.meterage.mapper.ResourceChargeRecordMapper;
import com.fitmgr.meterage.service.IResourceChargeRecordService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 启用计费项，定时禁用的资源进行计费
 *
 * @author zhangxiaokang
 * @date 2020/11/18 16:08
 */
@Slf4j
@Component
@Scope("prototype")
@AllArgsConstructor
public class EnableChargeItemJob extends XxlBaseTaskExec {
    @Override
    public ReturnT<String> taskCallback(Task task) throws Exception {

        // 获取任务Task当中的参数
        String metadata = task.getMetadata();
        if (StringUtils.isBlank(metadata)) {
            log.info("========= metadata is null! ========");
            return new ReturnT<>(0, null);
        }
        JSONObject jsonObject = JSONObject.parseObject(metadata);
        // 计费项uuid
        String chargeId = jsonObject.getString(ChargeConstant.CHARGE_ID);
        // 需要启用的资源数据
        String resourceData = jsonObject.getString(ChargeConstant.RESOURCE_CHARGE_DATA);
        if (StringUtils.isBlank(chargeId) || StringUtils.isBlank(resourceData)) {
            log.info("========== chargeId is null or resourceData is null , chargeId is {},resourceData is {}========", chargeId, resourceData);
            return new ReturnT<>(0, null);
        }
        ChargeItemMapper chargeItemMapper = SpringContextHolder.getBean(ChargeItemMapper.class);
        LambdaQueryWrapper<ChargeItem> itemLambdaQueryWrapper = Wrappers.<ChargeItem>lambdaQuery()
                .eq(ChargeItem::getUuid, chargeId)
                .eq(ChargeItem::getChargeStatus, EnableStatusEnum.ENABLE.getStatus())
                .eq(ChargeItem::getDelFlag, DeleteFlagStatusEnum.VIEW.getStatus());
        ChargeItem chargeItem = chargeItemMapper.selectOne(itemLambdaQueryWrapper);
        if (null == chargeItem) {
            log.info("chargeItem is null !");
            return new ReturnT<>(0, null);
        }
        // 入库资源String转换为Objct对象
        ResourceChargeRecord resourceChargeRecord = JSONObject.parseObject(resourceData, ResourceChargeRecord.class);
        if (null == resourceChargeRecord) {
            log.info(" resourceChargeRecord is null !");
            return new ReturnT<>(0, null);
        }

        // 查询是否已经有该资源启用信息，如果有则不能重复入库
        LambdaQueryWrapper<ResourceChargeRecord> queryWrapperDbResourceRecord = Wrappers.<ResourceChargeRecord>lambdaQuery()
                .eq(ResourceChargeRecord::getCmpInstanceName, resourceChargeRecord.getCmpInstanceName())
                .eq(ResourceChargeRecord::getDelFlag, DeleteFlagStatusEnum.VIEW.getStatus())
                .eq(ResourceChargeRecord::getEnableFlag, EnableStatusEnum.ENABLE.getStatus())
                .eq(ResourceChargeRecord::getResourceOffFlag, 0)
                .isNull(ResourceChargeRecord::getFinishUseTime);
        ResourceChargeRecordMapper resourceChargeRecordMapper = SpringContextHolder.getBean(ResourceChargeRecordMapper.class);
        ResourceChargeRecord dbChargeRecord = resourceChargeRecordMapper.selectOne(queryWrapperDbResourceRecord);
        if (dbChargeRecord != null) {
            log.info("========== 计费记录中已经存在该资源未结算的计费记录，不能重复入库==========，资源信息：{}", JSONObject.toJSONString(dbChargeRecord));
            return new ReturnT<>(0, null);
        }
        log.info(" resourceChargeRecord = {}", JSONObject.toJSONString(resourceChargeRecord));

        // 设置资源入库时间
        resourceChargeRecord.setBeginUseTime(LocalDateTime.now());
        resourceChargeRecord.setBillCycleTime(LocalDateTime.now());
        List<ResourceChargeRecord> enableResourceChargeRecordList = new ArrayList<>();
        enableResourceChargeRecordList.add(resourceChargeRecord);

        // 新增该计费记录入库（需要匹配新的计费项目，防止计费项发生变更但是还是用旧的计费项进行计费计算）
        IResourceChargeRecordService chargeBillDetailService = SpringContextHolder.getBean(IResourceChargeRecordService.class);
        ChargeItemDTO chargeItemDTO = new ChargeItemDTO();
        BeanUtils.copyProperties(chargeItem, chargeItemDTO);
        chargeItemDTO.setRemark(resourceChargeRecord.getCmpInstanceName() + " 新增计费记录！");
        chargeBillDetailService.insertChargeBill(enableResourceChargeRecordList, chargeItemDTO);
        return new ReturnT<>(0, null);
    }

    @Override
    public void taskRollback(Task task, Exception error) {

    }
}
