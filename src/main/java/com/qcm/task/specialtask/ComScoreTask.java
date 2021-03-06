package com.qcm.task.specialtask;

import com.qcm.es.entity.EsCompanyEntity;
import com.qcm.entity.OrgCompanyStatisticsInfo;
import com.qcm.task.maintask.ComPack;
import com.qcm.task.maintask.TaskType;
import com.qcm.task.stat.BrowseCount;
import com.qcm.task.stat.CompanyStatisticsInfo;
import com.qcm.util.MiscellanyUtil;
import com.qcm.dal.mybatis.MybatisClient;

public class ComScoreTask extends BaseTask {
    public ComScoreTask(TaskType key) {
        super(key);
    }

    public ComScoreTask(ComPack cp) {
        super(cp);
    }

    @Override
    public void run() {
        if (compack.es != null) {
            EsCompanyEntity c = compack.es;
            OrgCompanyStatisticsInfo info = MybatisClient.getCompanyStatisticsInfo(c.getOc_code());
            int count = MybatisClient.getBrowseCount(c.getOc_code());
            int score = 0;
            score += getScoreByArea(c);
            score += getScoreByContact(c);
            score += getScoreByName(c);
            score += getScoreByStatus(c);
            score += getScoreByType(c);
            score += getScoreByStatisticsInfo(info);
            score += getScoreByBrowse(count);
            double weight = Math.log(score+120);
            double min = Math.log(8);
            double max = Math.log(251);
            weight = (weight - min)/(max - min)/2 + 0.5;
            c.setWeight(weight);
            c.setScore(score);
        }

        countDown();
    }

    /**
     * I list all scoring criterion here
     * A: penalty
     * company operating status-abnormal:  -10
     * company name: too short and non-company e.g. 百度网吧: -100
     * company type: personal operating/self-employed organization: 0
     *             : other: -2
     *             : normal: 2
     *  min: -112, max: 2
     *
     * B: Award
     * company operating status-normal: +2
     * company statistics info: [0-5] for dimension; there are 21 dimensions totally: [0-105]
     * company located at (new)first-env city: 2
     * company with phone-numbers: 4
     * company with mails: 2
     * company browse count: [0-10]
     * company name: endswith "公司": 4
     *                        "合伙企业": 2
     *               contains "公司": 2
     *               other: 0
     * min: 0, max: 129
     *
     * ===================================
     * min: -112, max: 131
     * windows [-113, 131]  ===normalize===> [0,1]
     *
     * In query phase, sorting score = inner score * weight + score
     */

    public static int getScoreByName(EsCompanyEntity c) {
        String name = c.getOc_name();
        if (MiscellanyUtil.isBlank(name)) return -1000;
        if (name.endsWith("大学")) return 0;
        int score = 0;
        if (name.length() <= 4) {
            score += -100;
        }
        if (name.endsWith("公司")) {
            score += 4;
        } else if (name.contains("公司") || name.contains("合伙企业")) {
            score += 2;
        }
        return score;
    }

    public static int getScoreByStatus(EsCompanyEntity c) {
        int status = c.getOc_status();
        if (status <= 3 && status >= 1 || status == 10) return 2;
        return -3;
    }

    public static int getScoreByArea(EsCompanyEntity c) {
        String area = c.getOc_area();
        if (!MiscellanyUtil.isBlank(area)) {
            if (area.startsWith("11") || area.startsWith("12") || area.startsWith("31") || area.startsWith("3201")
                    || area.startsWith("3205") || area.startsWith("3301") || area.startsWith("4201")
                    || area.startsWith("4401")
                    || area.startsWith("4403") || area.startsWith("5001") || area.startsWith("5101")) return 2;
        }
        return 0;
    }

    public static int getScoreByType(EsCompanyEntity c) {
        String type = c.getOc_types().get(0);
        if ("其他".equals(type)) return -2;
        if ("个体工商户".equals(type)) return 0;
        return 2;
    }

    public static int getScoreByContact(EsCompanyEntity c) {
        int score = 0;
        if (!MiscellanyUtil.isArrayEmpty(c.getFix_phones())) {
            score += 4;
        } else if (!MiscellanyUtil.isArrayEmpty(c.getMobile_phones())) {
            score += 4;
        } else if (!MiscellanyUtil.isArrayEmpty(c.getMails())) {
            score += 2;
        }
        return score;
    }

    public static int getScoreByStatisticsInfo(OrgCompanyStatisticsInfo info) {
        if (info == null) return 0;
        int[] counts = new int[CompanyStatisticsInfo.StatInfo.size.ordinal()];
        counts[0] = info.GuDongXinXi;
        counts[1] = info.ZhuYaoRenYuan;
        counts[2] = info.BianGengXinXi;
        counts[3] = info.NianBao;
        counts[4] = info.ShangBiaoXinXi;
        counts[5] = info.ZhuanLiXinXi;
        counts[6] = info.RuanJianZhuZuoQuan;
        counts[7] = info.ZuoPinZhuZuoQuan;
        counts[8] = info.YuMingBeiAn;
        counts[9] = info.ShangPinTiaoMaXinXi;
        counts[10] = info.ChangShangBianMaXinXi;
        counts[11] = info.RenJianWei;
        counts[12] = info.XiaoFangJu;
        counts[13] = info.PanJueWenShu;
        counts[14] = info.FaYuanGongGao;
        counts[15] = info.BeiZhiXingRen;
        counts[16] = info.ShiXinRen;
        counts[17] = info.ZhanHuiHuiKan;
        counts[18] = info.ZhaoPin;
        counts[19] = info.DuiWaiTouZi;
        counts[20] = info.FenZhi;
        return CompanyStatisticsInfo.getScore(counts);
    }

    public static int getScoreByBrowse(int count) {
        return BrowseCount.getScore(count);
    }
}
