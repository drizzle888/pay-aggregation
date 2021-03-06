/* ------------------------------------------------------------------
 *   Product:      pay
 *   Module Name:  COMMON
 *   Package Name: com.gloryjie.pay.charge.service
 *   Date Created: 2018/12/9
 * ------------------------------------------------------------------
 * Modification History
 * DATE            Name           Contact
 * ------------------------------------------------------------------
 * 2018/12/9      Jie            GloryJie@163.com
 */
package com.gloryjie.pay.trade.service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.gloryjie.pay.base.enums.MqDelayMsgLevel;
import com.gloryjie.pay.base.exception.error.BusinessException;
import com.gloryjie.pay.base.util.BeanConverter;
import com.gloryjie.pay.channel.dto.ChannelPayQueryResponse;
import com.gloryjie.pay.channel.dto.param.ChargeCreateParam;
import com.gloryjie.pay.channel.dto.response.ChannelRefundResponse;
import com.gloryjie.pay.channel.enums.ChannelType;
import com.gloryjie.pay.channel.enums.PlatformType;
import com.gloryjie.pay.channel.service.ChannelGatewayService;
import com.gloryjie.pay.trade.biz.ChargeBiz;
import com.gloryjie.pay.trade.biz.RefundBiz;
import com.gloryjie.pay.trade.dao.ChargeDao;
import com.gloryjie.pay.trade.dao.RefundDao;
import com.gloryjie.pay.trade.dto.*;
import com.gloryjie.pay.trade.dto.param.ChargeQueryParam;
import com.gloryjie.pay.trade.dto.param.RefundParam;
import com.gloryjie.pay.trade.dto.param.RefundQueryParam;
import com.gloryjie.pay.trade.enums.ChargeStatus;
import com.gloryjie.pay.trade.enums.RefundStatus;
import com.gloryjie.pay.trade.error.TradeError;
import com.gloryjie.pay.trade.model.Charge;
import com.gloryjie.pay.trade.model.Refund;
import com.gloryjie.pay.trade.mq.TradeMqProducer;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * @author Jie
 * @since
 */
@Slf4j
@Service
public class ChargeServiceImpl implements ChargeService {

    @Autowired
    private ChargeDao chargeDao;

    @Autowired
    private RefundDao refundDao;

    @Autowired
    private ChannelGatewayService channelGatewayService;

    @Autowired
    private ChargeBiz chargeBiz;

    @Autowired
    private RefundBiz refundBiz;

    @Autowired
    private TradeMqProducer mqProducer;


    @Override
    public ChargeDto pay(ChargeCreateParam createParam) {
        ChannelType channel = createParam.getChannel();
        // ????????????????????????????????????
        channel.checkExtraParam(createParam.getExtra());
        // ??????????????????????????????
        List<Charge> chargeList = chargeDao.listByAppIdAndOrderNo(createParam.getAppId(), createParam.getOrderNo());
        Charge charge = checkChargeExist(chargeList, createParam);
        if (charge == null) {
            charge = chargeBiz.createChargeAndDistribute(createParam);
        }

        // ????????????
        mqProducer.sendTimingCloseMsg(charge.getChargeNo(), MqDelayMsgLevel.computeLevel(charge.getTimeExpire() * 60));

        return BeanConverter.covert(charge, ChargeDto.class);
    }

    @Override
    public ChargeDto queryPayment(Integer appId, String chargeNo) {
        Charge charge = chargeDao.getByAppIdAndChargeNo(appId, chargeNo);
        if (charge == null) {
            return null;
        }
        ChargeDto chargeDto;
        // ???????????????????????????,???????????????????????????
        if (ChargeStatus.WAIT_PAY.equals(charge.getStatus())) {
            // TODO: 2019/3/8 ????????????????????????????????????????????????????????????????????????
            charge = chargeBiz.queryChannel(charge);
        }
        chargeDto = BeanConverter.covert(charge, ChargeDto.class);
        // ?????????????????????????????????????????????
        if (!ChargeStatus.WAIT_PAY.equals(chargeDto.getStatus())) {
            chargeDto.setCredential(null);
        }
        return chargeDto;
    }


    @Override
    public RefundDto refund(RefundParam refundParam) {
        Charge charge = chargeDao.getByAppIdAndChargeNo(refundParam.getAppId(), refundParam.getChargeNo());
        if (charge == null || !charge.getStatus().canRefund()) {
            throw BusinessException.create(TradeError.CHARGE_NOT_EXISTS, "????????????????????????");
        }
        // ????????????
        Refund refund = refundBiz.asyncRefund(charge, refundParam);

        return BeanConverter.covert(refund, RefundDto.class);
    }

    @Override
    public List<RefundDto> queryRefund(@NonNull Integer appId, @NonNull String chargeNo, String refundNo) {
        // ?????????????????????????????????????????????,?????????????????????????????????
        if (StringUtils.isBlank(refundNo)) {
            List<Refund> refundList = refundDao.getByAppIdAndChargeNo(appId, chargeNo);
            return BeanConverter.batchCovert(refundList, RefundDto.class);
        }
        Refund refund = refundDao.getByAppIdAndRefundNo(appId, refundNo);
        if (refund == null) {
            return new ArrayList<>();
        }
        return Collections.singletonList(BeanConverter.covert(refund, RefundDto.class));
    }

    @Override
    public boolean handleChargeAsyncNotify(PlatformType platformType, Map<String, String> param) {
        // ????????????
        String chargeNo = getChargeNoFromNotifyParam(platformType, param);
        if (StringUtils.isBlank(chargeNo)) {
            return false;
        }
        Charge charge = chargeDao.load(chargeNo);
        if (charge == null) {
            log.warn("platform={} async trade notify, chargeNo={} not exist ", platformType.name(), chargeNo);
            return false;
        }
        log.info("ready to handle platform={} async trade notify, chargeNo={}", platformType.name(), chargeNo);
        if (ChargeStatus.SUCCESS == charge.getStatus()) {
            log.info("chargeNo={} status already SUCCESS,ignore platform={} async notify ", chargeNo, platformType.name());
            return true;
        }
        // ????????????????????????
        ChannelPayQueryResponse response = channelGatewayService.handleTradeAsyncNotify(charge.getAppId(), charge.getChannel(), param);
        RefreshChargeDto refreshChargeDto = chargeBiz.generateRefreshChargeDto(charge, response);
        charge = chargeBiz.refreshCharge(refreshChargeDto, charge);
        log.info("handle platform={} async trade notify, chargeNo={} completed chargeStatus={}", platformType.name(), chargeNo, charge.getStatus().name());
        param.put("appId", charge.getAppId().toString());
        return ChargeStatus.SUCCESS == charge.getStatus();
    }

    @Override
    public boolean handleRefundAsyncNotify(PlatformType platformType, Map<String, String> param) {
        String refundNo = getRefundNoFromNotifyParam(platformType, param);
        if (StringUtils.isBlank(refundNo)) {
            return false;
        }
        Refund refund = refundDao.load(refundNo);
        if (refund == null) {
            log.warn("platform={} async refund notify, refundNO={} not exist ", platformType.name(), refundNo);
            return false;
        }
        log.info("ready to handle platform={} async refund notify, refundNo={}", platformType.name(), refundNo);
        if (RefundStatus.SUCCESS == refund.getStatus()) {
            log.info("refundNo={} status already SUCCESS,ignore platform={} async refund notify ", refundNo, platformType.name());
            return true;
        }
        ChannelRefundResponse response = channelGatewayService.handleRefundAsyncNotify(refund.getAppId(), refund.getChannel(), param);
        RefreshRefundDto refreshRefundDto = refundBiz.generateRefreshRefundParam(refund, response);
        refund = refundBiz.refreshRefund(refreshRefundDto, refund);
        log.info("handle platform={} async refund notify, refundNo={} completed refundStatus={}", platformType.name(), refundNo, refund.getStatus().name());
        param.put("appId", refund.getAppId().toString());
        return RefundStatus.SUCCESS == refund.getStatus();
    }

    @Override
    public PageInfo<ChargeDto> queryPaymentList(ChargeQueryParam queryParam) {
        PageHelper.startPage(queryParam.getStartPage(), queryParam.getPageSize());
        // ???????????????????????????????????????????????????
        queryParam.setMaxAppId(queryParam.getAppId() / 100000 * 100000 + 99999);
        List<Charge> chargeList = chargeDao.getByQueryParam(queryParam);
        PageInfo pageInfo = PageInfo.of(chargeList);
        pageInfo.setList(BeanConverter.batchCovertIgnore(chargeList, ChargeDto.class));
        return pageInfo;
    }

    @Override
    public PageInfo<RefundDto> queryRefundList(RefundQueryParam queryParam) {
        PageHelper.startPage(queryParam.getStartPage(), queryParam.getPageSize());
        // ???????????????????????????????????????????????????
        queryParam.setMaxAppId(queryParam.getAppId() / 100000 * 100000 + 99999);
        List<Refund> refundList = refundDao.getByQueryParam(queryParam);
        PageInfo pageInfo = PageInfo.of(refundList);
        pageInfo.setList(BeanConverter.batchCovertIgnore(refundList, RefundDto.class));
        return pageInfo;
    }

    @Override
    public Map<ChargeStatus, StatCountDto> countPlatformTrade(Integer appId, LocalDateTime minTime, LocalDateTime maxTime) {
        Map<ChargeStatus, StatCountDto> result = new HashMap<>(6);
        Integer maxAppId = (appId / 100000 * 100000) + 99999;
        List<ChargeStatus> statuslList = Arrays.asList(ChargeStatus.SUCCESS, ChargeStatus.WAIT_PAY, ChargeStatus.CLOSED);
        for (ChargeStatus status : statuslList) {
            StatCountDto countDto = chargeDao.countByAppTreeAndStatusAndTime(appId, maxAppId, status, minTime, maxTime);
            countDto.setStatus(status);
            result.put(status, countDto);
        }
        return result;
    }

    @Override
    public StatCountDto countSubAppTradeInDay(Integer appId, ChargeStatus status, LocalDate date) {
        StatCountDto dto = chargeDao.countAppOneDayTrade(appId, status, date);
        if (dto.getCountNum() == null) {
            dto.setCountNum(0L);
        }
        if (dto.getTotalAmount() == null) {
            dto.setTotalAmount(0L);
        }
        return dto;
    }

    @Override
    public Map<ChannelType, StatCountDto> countAllChannelTradeInDay(Integer appId, ChargeStatus status, LocalDate date) {
        Map<ChannelType, StatCountDto> resultMap = new HashMap<>(18);
        Integer maxAppId = (appId / 100000 * 100000) + 99999;
        for (ChannelType channelType : ChannelType.values()) {
            StatCountDto dto = chargeDao.countAppTreeChannelOneDayTrade(appId, maxAppId, status, channelType, date);
            if (dto.getCountNum() == null) {
                dto.setCountNum(0L);
            }
            if (dto.getTotalAmount() == null) {
                dto.setTotalAmount(0L);
            }
            resultMap.put(channelType, dto);
        }

        return resultMap;
    }


    /**
     * ?????????????????????
     */
    private Charge checkChargeExist(List<Charge> chargeList, ChargeCreateParam param) {
        Charge existCharge = null;
        if (chargeList != null && chargeList.size() > 0) {
            for (Charge charge : chargeList) {
                // ???????????????????????????
                if (charge.getStatus().isPaid()) {
                    throw BusinessException.create(TradeError.ORDER_ALREADY_PAY);
                }
                // ????????????: ??????????????????,????????????????????????
                if (charge.getChannel().equals(param.getChannel())) {
                    existCharge = charge;
                }
            }

        }
        return existCharge;
    }

    /**
     * ????????????????????????chargeNo
     *
     * @param platformType
     * @param param
     * @return
     */
    private String getChargeNoFromNotifyParam(PlatformType platformType, Map<String, String> param) {
        switch (platformType) {
            case ALIPAY:
                return param.get("out_trade_no");
            case UNIONPAY:
                return param.get("orderId");
            default:
                return "";
        }
    }

    /**
     * ??????????????????????????????refundNo
     *
     * @param platformType
     * @param param
     * @return
     */
    private String getRefundNoFromNotifyParam(PlatformType platformType, Map<String, String> param) {
        switch (platformType) {
            case UNIONPAY:
                return param.get("orderId");
            default:
                return "";
        }
    }


}
