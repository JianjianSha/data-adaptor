package com.qcm.task.specialtask;

import com.qcm.es.entity.EsCompanyEntity;
import com.qcm.entity.OrgCompanyContact;
import com.qcm.task.maintask.TaskType;
import com.qcm.util.MiscellanyUtil;
import com.qcm.dal.mongodb.MongoClientRegistry;
import com.qcm.dal.mybatis.MybatisClient;
import com.qcm.entity.MongoComContact;

import java.util.*;

public class ComContactTask extends BaseTask {
    public ComContactTask(TaskType key) {
        super(key);
    }

    @Override
    public void run() {
        String oc_code = null;
        if (compack.es != null) {
            oc_code = compack.es.getOc_code();
        } else if (compack.arango != null) {
            oc_code = compack.arango.oc_code;
        } else if (compack.mongo != null) {
            oc_code = compack.mongo.get_id();
        }
        if (oc_code != null) {
            List<OrgCompanyContact> contacts = MybatisClient.getCompanyContacts(oc_code);
            if (compack.es != null) {
                EsCompanyEntity c = compack.es;
                List<String> m_phones = new ArrayList<>();
                List<String> f_phones = new ArrayList<>();
                List<String> mails = new ArrayList<>();
                for (OrgCompanyContact contact : contacts) {
                    if (MiscellanyUtil.isBlank(contact.oc_contact)) continue;
                    if (contact.oc_status != 1) continue;
                    if (contact.oc_type == 1) {
                        m_phones.add(contact.oc_contact);
                    } else if (contact.oc_type == 2) {
                        f_phones.add(contact.oc_contact);
                    } else if (contact.oc_type == 5) {
                        mails.add(contact.oc_contact);
                    }
                }
                c.setMobile_phones(m_phones);
                c.setFix_phones(f_phones);
                c.setMails(mails);
            }

//            if (compack.arango != null) {
//                List<String> cs = new ArrayList<>();
//                for (OrgCompanyContact contact : contacts) {
//                    if (MiscellanyUtil.isBlank(contact.oc_contact)) continue;
//                    if (contact.oc_status != 1) continue;
//
//
//                    cs.add(contact.oc_contact);
//                }
//                if (cs.size() > 0) {
//                    Set<String> codes = getMongoComContacts(cs);
//                    if (codes.size() > 1) {
//                        System.out.println(oc_code + " has same contacts with: " + String.join(",", codes));
//                        compack.arango.setContacts(codes);
//                    }
//                }
//            }
        }
        countDown();
    }

    private Set<String> getMongoComContacts(List<String> contacts) {
        List<MongoComContact> cs = MongoClientRegistry.client("contact")
                .findBy("contact", contacts, "code", "contact");
        Map<String, Set<String>> groups = new HashMap<>();
        for (MongoComContact c : cs) {
            Set<String> group = groups.get(c.contact);
            if (group == null) {
                group = new HashSet<>();
                groups.put(c.contact, group);
            }
            group.add(c.code);
        }
        Set<String> codes = new HashSet<>();
        for (String key : groups.keySet()) {
            Set<String> group = groups.get(key);
            if (group.size() <= 5) { // 5 is a threshold. if > 5, do not depend on contacts to aggregate persons
                codes.addAll(group);
            }
        }
        return codes;
    }
}
