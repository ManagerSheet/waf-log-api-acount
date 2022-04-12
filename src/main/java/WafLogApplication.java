import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class WafLogApplication {
    public static void main(String[] args) {
        ///Users/xixixiong/Downloads/data_20220408_145221.txt
        String input = args[0];

        //输出文件 xlsx
        String output = args[1];
        //2022-10-26 10:00:01~2022-10-26 10:00:20
        String timeRange = args[2];

        //解析log文件
        List<Request> requests = transfer2Request(input);
        //判断 requests不能为空

        //获取时间纬度
        List<String> dates = timeRange(timeRange);


        //框定时间范围
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        requests = requests.stream()
                .filter(r->dates.contains(sdf.format(r.getTime())))
                .collect(Collectors.toList());

        //处理requestpath映射 如果没有映射就拿requestpath作为key,value
        final Map<String, String> mappingPath = mappingPath(args.length==4?args[3]:null);
        if(mappingPath == null || mappingPath.isEmpty()){
            mappingPath.putAll(requests.stream()
                    .collect(Collectors.toMap(Request::getRequestPath,Request::getRequestPath,(x,y)->x)));
        }else{
            requests.stream()
                    .filter(x -> !mappingPath.containsKey(x.getRequestPath()))
                    .forEach(x->{
                        mappingPath.put(x.getRequestPath(),"others");
                    });
        }

        //最终结果对象Map<path,Map<time,count>>
        // 例如 /ec-mybmw/point/order/v1/normal-create 会映射车order-create 在01:44:55访问20次 那么结果是Map<"order-create",<"01:44:55",20>>
        Map<String,Map<String,Integer>> results = new HashMap<>();

        //保证表头数据和时间范围一致，避免有url在某一秒没有数据，内容为空
        requests.stream().forEach(x->{
            String key = mappingPath.get(x.getRequestPath());
            Map<String,Integer> map = new TreeMap<>();
            dates.stream().forEach(y->{
                map.put(y,0);
            });
            results.put(key,map);
        });

        //最终结果对象填充数据
        requests.stream().forEach(x->{
            String key = mappingPath.get(x.getRequestPath());
            String timekey = sdf.format(x.getTime());
            Map<String, Integer> integerMap = results.get(key);
            integerMap.put(timekey,integerMap.get(timekey)+1);
        });

        genExcel(dates, results,output);

    }

    /**
     * 生成excel
     * @param dates
     * @param results
     */
    private static void genExcel(List<String> dates, Map<String, Map<String, Integer>> results,String output) {
        Workbook wb = new XSSFWorkbook();
        //设置基础样式
        CellStyle centerStyle = wb.createCellStyle();
        centerStyle.setAlignment(HorizontalAlignment.CENTER);
        CellStyle rightStyle = wb.createCellStyle();
        rightStyle.setAlignment(HorizontalAlignment.RIGHT);

        Sheet sheet = wb.createSheet("API统计");
        //创建表头行
        sheet.setColumnWidth(0,50*256);
        Row row = sheet.createRow(0);
        row.setHeightInPoints(30f);
        for(int i = 0; i< dates.size(); i++){
            Cell cell = row.createCell(i+1);
            cell.setCellStyle(centerStyle);
            sheet.setColumnWidth(i+1,10*256);
            cell.setCellValue(dates.get(i).substring(10));
        }
        //创建每一行每一列数据
        int i=1;
        for(Map.Entry<String,Map<String,Integer>> entry: results.entrySet()) {
            Row rown = sheet.createRow(i);
            Cell cell = rown.createCell(0);
            cell.setCellValue(entry.getKey());
            int k=1;
            for (Map.Entry<String, Integer> entry1 : entry.getValue().entrySet()) {
                Cell celln = rown.createCell(k);
                celln.setCellStyle(rightStyle);
                celln.setCellValue(entry1.getValue());
                k++;
            }
            i++;
        }

        //创建合计
        Row lastRow = sheet.createRow(i );
        Cell cell = lastRow.createCell(0);
        cell.setCellValue("总计(QPS)");
        cell.setCellStyle(rightStyle);
        for(int j = 1; j<= dates.size(); j++){
            lastRow.createCell(j);
            String colString = CellReference.convertNumToColString(j);
            String sumstring = "SUM(" + colString +"2:" + colString + (i) + ")";//求和公式
            sheet.getRow(i).getCell(j).setCellFormula(sumstring);
        }

        //生成文件
        try(FileOutputStream outputStream = new FileOutputStream(output);){
            wb.write(outputStream);
            wb.close();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    /**
     * 处理path在报表中的映射
     * @param mappingPath 映射文件路径
     * @return
     */
    public static Map<String, String> mappingPath(String mappingPath){
        Map<String,String> map = new HashMap<>();
        if(mappingPath == null){
            return map;
        }

        File file = new File(mappingPath);
        List<MappingPath> paths = null;
        try(InputStream is = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();){
            byte[] buffer = new byte[1024];
            int len =0;
            while ((len = is.read(buffer, 0, buffer.length)) != -1)
            {
                out.write(buffer, 0, len);
            }
            String pathJson = new String(out.toByteArray());

            paths = JSON.parseObject(pathJson,
                    new TypeReference<List<MappingPath>>(){});

        }catch (Exception e){
            e.printStackTrace();
        }
        //处理最终结果

        paths.forEach(x->{
            x.getUrls().forEach(y->{
                map.put(y,x.getMappingPath());
            });
        });
        return map;
    }

    /**
     * 解析报表的时间纬度
     * @param timeRange 时间范围
     * @return java.util.List<java.lang.String>
     */
    private static List<String> timeRange(String timeRange) {
        if(timeRange == null){
            return null;
        }
        String[] timeArray = timeRange.split("~");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date start = null;
        Date end = null;
        try {
            start = sdf.parse(timeArray[0]);
            end = sdf.parse(timeArray[1]);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        List<String> dates = new ArrayList<>();
        long l = (end.getTime() - start.getTime())/1000;
        int i =0;
        Calendar c = Calendar.getInstance();
        while(i<=l){
            c.setTime(start);
            c.add(Calendar.SECOND,i);
            dates.add(sdf.format(c.getTime()));
            i++;
        }
        return dates;
    }

    /**
     * 解析输入log文件，转换成集合对象
     * @param input 文件路径
     * @return List
     */
    public static List<Request> transfer2Request(String input) {
        File file = new File(input);
        List<Request> list = new ArrayList();
        try(BufferedReader bf = new BufferedReader(new FileReader(file))){
            String line="";
            while ((line=bf.readLine())!=null){
                Request request = JSON.parseObject(line, Request.class);
                list.add(request);
            }
        }catch(Exception e){
            e.printStackTrace();
        }
        return  list;
    }
}
