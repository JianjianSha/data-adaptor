package com.qianzhan.qichamao.task.com;

import com.arangodb.entity.BaseDocument;
import com.arangodb.entity.BaseEdgeDocument;
import com.qianzhan.qichamao.config.GlobalConfig;
import com.qianzhan.qichamao.dal.arangodb.ArangoBusinessRepository;
import com.qianzhan.qichamao.dal.arangodb.ArangoInterveneRepository;
import com.qianzhan.qichamao.dal.mybatis.MybatisClient;
import com.qianzhan.qichamao.entity.OrgCompanyDimBatch;
import com.qianzhan.qichamao.entity.OrgCompanyList;
import com.qianzhan.qichamao.graph.ArangoBusinessCompany;
import com.qianzhan.qichamao.graph.ArangoBusinessPack;
import com.qianzhan.qichamao.graph.ArangoBusinessPerson;
import com.qianzhan.qichamao.graph.ArangoWriter;
import com.qianzhan.qichamao.util.MiscellanyUtil;
import com.qianzhan.qichamao.util.NLP;
import com.qianzhan.qichamao.util.parallel.Master;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.Function;

public class MainTaskArangodbCompany extends MainTaskBase {
//    private static ArangoComClient client = ArangoComClient.getSingleton();

    private int path_thre;
    private int max_traverse_depth;
    public static int dist_step = 1000;
    @Deprecated
    private int subTableIndex;
    @Deprecated
    private List<String> subTableNames;
    // lp:0    sh:1    sm:1
    @Deprecated
    private int lp_sh_sm;


    public void insert() {
        insert_static(this.task);
    }

    public static void insert_static(TaskType task) {
        try {
            ArangoWriter.insert(SharedData.getBatch(task));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public MainTaskArangodbCompany() throws Exception {
        super("config/Task_Arango_Company.txt");
        checkpointName = "data-adaptor.arango.company";
        dist_step = config.getInt("dist_step", 1000);
        ArangoBusinessRepository.singleton();       // preheat this object
    }



    protected void state1_pre() throws Exception {
        ArangoBusinessRepository.singleton().createGraph();
        preHooks = new ArrayList<>();
        postHooks = new ArrayList<>();
        preHooks.add(() -> SharedData.openBatch(task));

        postHooks.add(() -> SharedData.closeBatch(task));
    }

    /**
     * this is the normal method/process: read OrgCompanyList,
     *  and then multi-threadly read legal person, share holder and senior member
     *  but, only insert/insert legal person, share holder and senior member into Arangodb.
     *
     *  checkpoint: 142248249
     * @return
     * @throws Exception
     */
    protected boolean state1_inner() throws Exception {
        List<OrgCompanyList> companies = MybatisClient.getCompanies(checkpoint, batch);
        if (companies.size() == 0) return false;

        SubTaskComBase.resetLatch(task, batch*3);
        int count = 0;
        for (OrgCompanyList company : companies) {
            if (company.oc_id > checkpoint) checkpoint = company.oc_id;

            if (!validateCode(company.oc_code)) continue;
            String area = company.oc_area;
            if (area.startsWith("71") || area.startsWith("81") || area.startsWith("82")) {
                continue;
            }
            company.oc_name = company.oc_name.trim();
            if (filter_out(company.oc_name)) continue;

            SharedData.open(task);
            ComPack cp = SharedData.get(task);

            ArangoBusinessPack a_com = cp.arango;
            a_com.oc_code = company.oc_code;
            a_com.oc_area = company.oc_area;
            a_com.com = new ArangoBusinessCompany(company.oc_code, company.oc_name, company.oc_area).to();

            //
            pool.execute(new SubTaskComDtl(task));
            pool.execute(new SubTaskComMember(task));
            pool.execute(new SubTaskComShareHolder(task));
//            pool.execute(new SubTaskComContact(task));

            SharedData.close(task);
            count++;
        }
        // wait for all sub-tasks finishing
        int diff = (batch-count)*3;
        while(SubTaskComBase.getLatch(task).getCount() != diff) {
            Thread.sleep(50);
        }

        insert();
        return true;
    }

    protected void state4_pre() throws Exception {
        path_thre = config.getInt("path_thre", 3);
        max_traverse_depth = config.getInt("max_traverse_depth", path_thre);
    }


    /**
     * combine vertices with a same name
     * @return
     * @throws Exception
     */
    protected boolean state4_inner() throws Exception {
        List<OrgCompanyList> companies = MybatisClient.getCompanies(checkpoint, batch);
        if (companies.size() == 0) return false;

//        List<String> toRemoved = new ArrayList<>();
        for (OrgCompanyList company : companies) {
            if (company.oc_id > checkpoint) checkpoint = company.oc_id;

            if (!validateCode(company.oc_code)) continue;
            String area = company.oc_area;
            if (area.startsWith("71") || area.startsWith("81") || area.startsWith("82")) {
                continue;
            }
            company.oc_name = company.oc_name.trim();
            if (filter_out(company.oc_name)) continue;

            // delete edge of type 4 -> share the same contacts
            // early we think if two companies share a same contact, there is some relation between them;
            //  however, this guess is not correct at most time.
//            toRemoved.addAll(getEdgeByContact(company.oc_code));  // remove once a time
//            codes.add(company.oc_code);                         // collect all and then remove them once a batch


            combine(company.oc_code, company.oc_id);
        }

//        toRemoved.addAll(getEdgeByContact_Parallel(codes));     // collect all edges
//
//        if (toRemoved.size() > 0) {
//            System.out.println(String.format("%d edges by contact will be removed.", toRemoved.size()));
//            client.bulkDelete_ED(toRemoved);
//        }
        return true;
    }


    /**
     * collect all edges with type `contact` according to a group of code vertices
     * collecting is executed in parallel
     * @param codes
     * @return
     */
    private Collection<String> getEdgeByContact_Parallel(List<String> codes) {
        int workers = 10;
        Function<String, List<String>> collector = code -> getEdgeByContact(code);
        LinkedList<String> queue = new LinkedList<>();
        queue.addAll(codes);
        Master<String, List<String>> master = new Master<String, List<String>>(workers, queue, collector);
        List<List<String>> results = master.start();
        Set<String> keys = new HashSet<>();
        for (List<String> list: results) {
            for (String result : list) {
                keys.add(result);
            }
        }
//
        return keys;
    }

    /**
     * collect all edges with type `contact` according to a code vertex
     * @param oc_code
     * @return
     */
    private List<String> getEdgeByContact(String oc_code) {
        List<String> toRemoved = new ArrayList<>();
        try {
            ArangoBusinessRepository cp = ArangoBusinessRepository.singleton();
            String id = cp.getGraphMeta().froms()[0] + "/" + oc_code;
            List<BaseEdgeDocument> edges = cp.neighbours(id);

            if (!MiscellanyUtil.isArrayEmpty(edges)) {
                for (BaseEdgeDocument edge : edges) {
                    Map<String, Object> props = edge.getProperties();
                    Object tp = props.get("type");
                    if (tp != null) {
                        long tp_l = (Long) tp;
                        if (tp_l == 4) {
                            toRemoved.add(edge.getKey());
                        }
                    }
                }
            }
        } catch (Exception e) {

        }
        return toRemoved;
    }

    // it is used to accelerate vertex merging for those clusters with large size
    private Set<String> trivials = new HashSet<>();
    /**
     * combine two vertices with a same name
     * @param code oc_code of a vertex in a chain
     */
    private void combine(String code, int idx) throws Exception {
        if (trivials.contains(code)) {
            trivials.remove(code);
            return;
        }

        boolean notPrint = true;

        ArangoInterveneRepository intervene = ArangoInterveneRepository.singleton();
        ArangoBusinessRepository business = ArangoBusinessRepository.singleton();
        // because there may be many vertices connect to the code vertex, so we set max_deep=2 here
        List<BaseDocument> vertices = business.connectedGraph(ArangoBusinessCompany.toId(code), 3);
        if (MiscellanyUtil.isArrayEmpty(vertices)) return;
        int graphSize = vertices.size();

        // key: company vertex id, value: its all neighbours
        Map<String, Set<String>> companyWithNeighbours = new HashMap<>();
        if (graphSize > 12000) {
            // print `trivials` size in time
            if (trivials.size() > 20000) {
                System.out.println(String.format("\033[31;4mtrivials size %d\033[0m", trivials.size()));
            }
            List<String> toIds = new ArrayList<>();     // collect all company-vertices
            for (BaseDocument vertex : vertices) {      // traverse all vertices on this graph
                if (vertex == null) continue;
                if (vertex.getKey().length() == 9) {    // company
                    toIds.add(vertex.getId());
                }

                if (toIds.size() >= 256) {              // if company-vertices is too many, search them batch by batch
                    List<BaseEdgeDocument> edges = business.searchByTos(toIds);
                    for (BaseEdgeDocument edge : edges) {
                        if (edge == null) continue;
                        String from = edge.getFrom();

                        Set<String> group = companyWithNeighbours.get(edge.getTo());
                        if (group == null) {
                            group = new HashSet<>();
                            companyWithNeighbours.put(edge.getTo(), group);
                        }
                        group.add(from);
                    }
                    toIds.clear();
                }
            }
            if (toIds.size() > 0) {                 // do not forget the tail batch
                List<BaseEdgeDocument> edges = business.searchByTos(toIds);
                for (BaseEdgeDocument edge : edges) {
                    if (edge == null) continue;
                    String from = edge.getFrom();

                    Set<String> group = companyWithNeighbours.get(edge.getTo());
                    if (group == null) {
                        group = new HashSet<>();
                        companyWithNeighbours.put(edge.getTo(), group);
                    }
                    group.add(from);
                }
            }
        }

        // after get all _from of `code` and directly connected companies of code
        // group vertex ids by personal name(actually name_md5)
        Map<String, List<BaseDocument>> groups = new HashMap<>(); // keep all natural persons and unknown companies
        int personNum = 0;      // number of all person vertices
        for (BaseDocument vertex : vertices) {
            if (vertex == null) continue;
            // exclude the vertex that will be handled later
            for (Set<String> neighbours : companyWithNeighbours.values()) {
                neighbours.remove(vertex.getId());  //
            }

            String key = vertex.getKey();   //.split("/")[1];
            if (key.length() <= 9) continue;    // company-type node, do not need to merge vertex, skip
            if (GlobalConfig.getEnv() == 2 && vertex.getId().startsWith(ArangoBusinessCompany.collection)) continue;



            personNum++;
            String name_md5 = key.substring(9);
            List<BaseDocument> group = groups.get(name_md5);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(name_md5, group);
            }
            group.add(vertex);  // person vertices which share a same person name
        }

        // check out those companies whose all neighbours are already included in this current
        //  connected graph represented by `vertices`.
        //  Those companies have no vertices to be merged after current merging
        StringBuilder existing_sql = new StringBuilder();
        boolean finished = false;
        for (String key : companyWithNeighbours.keySet()) {
            if (companyWithNeighbours.get(key).size() == 0) {
                String trivial = key.split("/")[1];
                if (existing_sql.length() == 0) {
                    existing_sql.append("select oc_id, oc_code from OrgCompanyList where oc_code in ('")
                            .append(trivial).append("'");
                } else {
                    existing_sql.append(", '").append(trivial).append("'");
                }

                if (existing_sql.length() > 5000) {
                    existing_sql.append(")");
                    List<Map<String, Object>> docs = MybatisClient.selectMany(existing_sql.toString());
                    for (Map<String, Object> doc : docs) {
                        Integer oc_id = (Integer) doc.get("oc_id");
                        String oc_code = (String) doc.get("oc_code");
                        if (oc_id != null) {
                            int id = oc_id;
                            if (id < idx) {
                                trivials.remove(oc_code);
                            } else if (id == idx) {
                                finished = true;
                                trivials.remove(oc_code);
                            } else {
                                trivials.add(oc_code);
                            }
                        }
                    }
                    existing_sql.delete(0, existing_sql.length());
                }
            }
        }
        if (existing_sql.length() > 0) {
            existing_sql.append(")");
            List<Map<String, Object>> docs = MybatisClient.selectMany(existing_sql.toString());
            for (Map<String, Object> doc : docs) {
                Integer oc_id = (Integer) doc.get("oc_id");
                String oc_code = (String) doc.get("oc_code");
                if (oc_id != null) {
                    int id = oc_id;
                    if (id < idx) {
                        trivials.remove(oc_code);
                    } else if (id == idx) {
                        finished = true;
                        trivials.remove(oc_code);
                    } else {
                        trivials.add(oc_code);
                    }
                }
            }
        }
        if (finished) return;



        // key: oc_code, value: it's all person vertex's id
        Map<String, Set<String>> intervenes = new HashMap<>();

        for (String name_md5 : groups.keySet()) {
            List<BaseDocument> group = groups.get(name_md5);
            if (group.size() == 1) continue;    // no more than 2 person shares the same name, skip

            // can we directly combine a group since they all share a same person name?
            // YES, we can because here the max depth of traversing is 2 which has met the lower limit,
            //  however, it deserves to note the existence of intervene.
            //  To this end, the intervene hasn't been started for using, so just combine them directly temporarily
            String new_from_id = null;
            List<String> old_from_ids = new ArrayList<>();
            for (BaseDocument doc : group) {
                if (new_from_id == null) new_from_id = doc.getId();
                else old_from_ids.add(doc.getId());
            }

            business.merge(old_from_ids, new_from_id, false);
        }
    }

    /**
     * update vertex degree
     * only person vertices are concerned at current time.
     */
    private boolean updateDegree() throws Exception {
        ArangoBusinessRepository business = ArangoBusinessRepository.singleton();
        List<BaseDocument> documents = business.scan(ArangoBusinessPerson.collection, checkpoint, batch);

        List<String> ids = new ArrayList<>();           // stores person vertices
        Map<String, Integer> map = new HashMap<>();     //
        for (BaseDocument document : documents) {
            if (document == null) continue;
            if (GlobalConfig.getEnv() == 1) {
                Object type = document.getAttribute("type");
                if (type != null) {
                    Long type_l = (Long) type;
                    if (type_l == 1) continue;
                }
            }
            ids.add(document.getId());
            map.put(document.getId(), 0);
        }
        List<BaseEdgeDocument> fromEdges = business.searchByFroms(ids);
        if (fromEdges == null) return documents.size() > 0;

        for (BaseEdgeDocument edge : fromEdges) {
            if (edge == null) continue;
            String from = edge.getFrom();   // parallel edges should be counted repeatedly.
            Integer count = map.get(from);
            if (count == null) map.put(from, 1);
            else map.put(from, count+1);
        }
        StringBuilder sb = new StringBuilder();

        for (String id : map.keySet()) {
            if (sb.length() == 0) {
                sb.append("LET persons = [{_key: '");
                sb.append(id.split("/")[1]).append("', c: ").append(map.get(id)).append("}");
            } else {
                sb.append(", {_key: '").append(id.split("/")[1]).append("', c: ").append(map.get(id)).append("}");
            }
        }
        sb.append("]\nFOR v in persons UPDATE v WITH {degree: v.c} IN ")
                .append(ArangoBusinessPerson.collection).append(" RETURN NEW");
        String aql = sb.toString();
        System.out.println(aql);
        List<BaseDocument> docs = business.execute(aql);

        for (BaseDocument doc : docs) {
            String name = (String) doc.getAttribute("name");
            Long degree = (Long) doc.getAttribute("degree");
            long d = degree == null ? -1 : (long) degree;
            System.out.println(String.format("document %s-%s has degree %d", doc.getKey(), name, d));
        }
        checkpoint += batch;
        return documents.size() > 0;
    }

    protected void state2_pre() throws Exception {
        // todo update ArangoDB `company-person-relation`
    }

    /**
     * update all data in ArangoDB day by day.
     * This state is corresponding to state1 which usually means writing data into
     * database firstly.
     * @return
     * @throws Exception
     */
    protected boolean state2_inner() throws Exception {
        // todo update ArangoDB `company-person-relation`
        // pop data from synchronized table
        List<OrgCompanyList> companies = new ArrayList<>();

        if (companies.size() == 0) return false;

        SubTaskComBase.resetLatch(task, batch*3);
        int count = 0;
        for (OrgCompanyList company : companies) {
            if (!validateCode(company.oc_code)) continue;

            company.oc_name = company.oc_name.trim();
            if (filter_out(company.oc_name)) continue;

            SharedData.open(task);
            ComPack cp = SharedData.get(task);

            ArangoBusinessPack a_com = cp.arango;
            a_com.oc_code = company.oc_code;
            a_com.oc_area = company.oc_area;
//            arango.com = new ArangoBusinessCompany(company.oc_code, company.oc_name, company.oc_area);

            //
            pool.execute(new SubTaskComDtl(task));
            pool.execute(new SubTaskComMember(task));
            pool.execute(new SubTaskComShareHolder(task));
//            pool.execute(new SubTaskComContact(task));

            SharedData.close(task);
            count++;
        }
        // wait for all sub-tasks finishing
        int diff = (batch-count)*3;
        while(SubTaskComBase.getLatch(task).getCount() != diff) {
            Thread.sleep(100);
        }

        ArangoWriter.update(SharedData.getBatch(task));
        return true;
    }

    @Override
    protected void state6_pre() throws Exception {
    }

    /**
     * update degree of vertices
     * @return
     * @throws Exception
     */
    protected boolean state6_inner() throws Exception {
        ArangoBusinessRepository business = ArangoBusinessRepository.singleton();
        int company_vertex_id_length = ArangoBusinessCompany.collection.length()+10;

        List<OrgCompanyList> companies = MybatisClient.getCompanies(checkpoint, batch);
        if (companies.size() == 0) return false;

        // key: vertex key, value: vertex out-in degree
        Map<String, Integer> map = new HashMap<>();     //
        List<String> keys = new ArrayList<>();
        for (OrgCompanyList company : companies) {
            if (company.oc_id > checkpoint) checkpoint = company.oc_id;

            if (!validateCode(company.oc_code)) continue;
            String area = company.oc_area;
            if (area.startsWith("71") || area.startsWith("81") || area.startsWith("82")) {
                continue;
            }
            company.oc_name = company.oc_name.trim();
            if (filter_out(company.oc_name)) continue;

            keys.add(company.oc_code);
            map.put(ArangoBusinessCompany.collection+"/"+company.oc_code, 0);
        }
        if (map.size() > 0) {
            List<BaseEdgeDocument> edges = business.searchByTos(map.keySet());
            if (edges == null) return true;

            for (BaseEdgeDocument edge : edges) {
                String id = edge.getFrom();
                if (!map.containsKey(id)) {
                    map.put(id, 0);
                    keys.add(id.split("/")[1]);
                }
            }
            Collection<BaseDocument> vertices = business.get(ArangoBusinessPerson.collection, keys);
            if (vertices == null) return true;
            for (BaseDocument vertex : vertices) {
                Long degree = (Long) vertex.getAttribute("degree");
                if (degree != null && degree > 0) {
                    String id = vertex.getId();
                    map.remove(id);
                }
            }
            edges = business.searchByEnds(map.keySet(), 3);
            if (edges == null) return true;

            for (BaseEdgeDocument edge : edges) {
                if (edge == null) continue;
                String from = edge.getFrom();   // parallel edges should be counted repeatedly.
                Integer count = map.get(from);
                if (count == null) map.put(from, 1);
                else map.put(from, count+1);
            }

            StringBuilder sb = new StringBuilder();

            for (String id : map.keySet()) {
                if (sb.length() == 0) {
                    sb.append("LET persons = [{_key: '");
                    sb.append(id.split("/")[1]).append("', c: ").append(map.get(id)).append("}");
                } else {
                    sb.append(", {_key: '").append(id.split("/")[1]).append("', c: ").append(map.get(id)).append("}");
                }
            }
            sb.append("]\nFOR v in persons UPDATE v WITH {degree: v.c} IN ")
                    .append(ArangoBusinessPerson.collection).append(" RETURN NEW");
            String aql = sb.toString();
            List<BaseDocument> docs = business.execute(aql);

//            System.out.println(aql);
            String suffix = "\033[0m";
            String prefix = "\033[31;1m";
            for (BaseDocument doc : docs) {
                Long degree = (Long) doc.getAttribute("degree");
                long d = degree == null ? -1 : (long) degree;
                if (d < 10) continue;
                String name = (String) doc.getAttribute("name");
                System.out.println(String.format("document %s-%s has degree %s %d %s",
                        doc.getKey(), name, prefix, d, suffix));
            }
        }
        return true;
    }
    @Deprecated
    protected boolean state4_inner_deprecated() throws Exception {
        List<OrgCompanyDimBatch> docs = getLpShSm();
        if (docs == null) return false;     // all sub tables have been read over
        while (docs.size() == 0) {      // current sub table has been read over
            subTableIndex++;
            checkpoint = 0;
            // store the record point
            updateSubtable();
            MybatisClient.updateCheckpoint(checkpointName, checkpoint);
            docs = getLpShSm();
            if (docs == null) return false;
        }
        // read a batch successfully
        for (OrgCompanyDimBatch doc : docs) {
            checkpoint = doc.a_id;
            if (doc.a_status == 4) continue;
            String name = doc.a_name.trim();
            if (MiscellanyUtil.isBlank(name)) continue;

            int flag = NLP.recognizeName(name);
            // not completed...
        }

        return true;
    }
    @Deprecated
    private List<OrgCompanyDimBatch> getLpShSm() {
        List<OrgCompanyDimBatch> docs = null;
        if (subTableIndex == 0) {
            docs = MybatisClient.getCompanyDtls(checkpoint, batch);
            lp_sh_sm = 0;
        } else if (subTableIndex == 1) {        // member
            docs = MybatisClient.getCompanyMemberBatch(checkpoint, batch);
            lp_sh_sm = 2;
        } else if (subTableIndex == 2) {        // share holder
            docs = MybatisClient.getCompanyGDBatch(checkpoint, batch);
            lp_sh_sm = 1;
        } else if (subTableIndex < subTableNames.size()) {
            String table = subTableNames.get(subTableIndex);
            String[] segs = table.split("_");
            if (segs.length == 2) {
                String tail = segs[segs.length - 1];
                if (segs[0].endsWith("GD")) {
                    docs = MybatisClient.getCompanyGDGsxtBatch(checkpoint, batch, tail);
                    lp_sh_sm = 1;
                } else if (segs[0].endsWith("Mgr")) {
                    docs = MybatisClient.getCompanyMemberGsxtBatch(checkpoint, batch, tail);
                    lp_sh_sm = 2;
                }
            }
        }
        return docs;
    }
    @Deprecated
    private void updateSubtable() throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(Paths.get("tmp/Arangodb_state4.txt"),
                Charset.forName("utf-8"))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.startsWith("sub_table_index")) {
                    lines.add(String.format("sub_table_index=%d\n", subTableIndex));
                } else {
                    lines.add(line);
                }
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get("tmp/arango_state4.txt"),
                Charset.forName("utf-8"), StandardOpenOption.WRITE)) {
            for (String line : lines) {
                writer.write(line);
            }
        }
    }
    @Deprecated
    private void readSubtable() throws Exception {
        subTableNames = new ArrayList<>();
        File f = new File("tmp/arango_state4.txt");
        if (!f.exists()) {
            System.out.println("file storing sub table names do not exists. It will load from database...");
            subTableIndex = 0;
            // read table
            List<String> tables = MybatisClient.getGsxtSubtableNames();

            subTableNames.add("OrgCompanyDtl");         // from which we read legal person
            subTableNames.add("OrgCompanyDtlMgr");      // from which we read shenzhen's senior member
            subTableNames.add("OrgCompanyDtlGD");       // from which we read shenzhen's share holder
            for (String table : tables) {
                if (table.startsWith("OrgCompanyGsxtDtlGD_")
                        || table.startsWith("OrgCompanyGsxtDtlMgr_")) {
                    subTableNames.add(table);
                }
            }
            // persist on disk
            try (BufferedWriter writer = Files.newBufferedWriter(Paths.get("tmp/arango_state4.txt"),
                    Charset.forName("utf-8"))) {
                writer.write(String.format("sub_table_index=%d\n", subTableIndex));
                writer.write("# sub table names must be located at last.\n");
                writer.write("sub_table_names:\n");
                for (String name : subTableNames) {
                    writer.write(name+"\n");
                }
            } // throw out exception
        } else {
            try (BufferedReader reader = Files.newBufferedReader(Paths.get("tmp/Arangodb_state4.txt"),
                    Charset.forName("utf-8"))) {
                String line = null;
                boolean subTableStarts = false;
                while((line = reader.readLine()) != null) {
                    if (MiscellanyUtil.isBlank(line) || line.startsWith("#")) continue;

                    if (line.startsWith("sub_table_names:")) {
                        subTableStarts = true;
                    } else if (subTableStarts) {
                        subTableNames.add(line.trim());
                    } else {
                        String[] segs = line.trim().split("=", 2);
                        if (segs[0].equals("sub_table_index")) {
                            subTableIndex = Integer.parseInt(segs[1]);
                        }
                    }
                }
            } // throw out exception
        }
    }

}
