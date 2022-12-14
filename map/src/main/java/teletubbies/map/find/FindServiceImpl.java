package teletubbies.map.find;

import lombok.SneakyThrows;
import org.json.XML;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ParallelFlux;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FindServiceImpl implements FindService {
    @Value("${TMAP_APPKEY}")
    private String tmap_apiKey; //티맵 API 앱키 설정

    @Value("${ELEVATOR_APPKEY}")
    private String elevator_apikey; //엘리베이터 API 키 설정

    @Value("${TMAP_URL}")
    private String tmap_url;

    @Value("${TMAP_RG_URL}")
    private String tmap_rg_url;

    @Value("${ELEVATOR_URL}")
    private String elevator_url;

    @Value("${STAIR_URL}")
    private String stair_url;

    @SneakyThrows
    public List<FindDto> findAddressByTmapAPI(String FindName, double longitude, double latitude) { // 티맵 api (통합검색(명칭검색))

//        long start2 = System.currentTimeMillis();
//        long start0 = System.currentTimeMillis();
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory(tmap_url);
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);

        WebClient wc = WebClient.builder().uriBuilderFactory(factory).baseUrl(tmap_url).build();

        String encodedName = URLEncoder.encode(FindName, "UTF-8");

        ResponseEntity<String> result = wc.get()
                .uri(uriBuilder -> uriBuilder.path("/tmap/pois")
                        .queryParam("version", 1) //버전
                        .queryParam("searchKeyword", encodedName) // 검색 키워드
                        .queryParam("count", 10) // 개수
                        .queryParam("appKey", tmap_apiKey) // 서비스키
                        .queryParam("searchtypCd", "A") // 거리순, 정확도순 검색(거리순 : R, 정확도순 : A)
                        .queryParam("radius", 0) // 반경( 0: 전국반경)
                        .queryParam("centerLon", longitude) // 중심 좌표의 경도 좌표
                        .queryParam("centerLat", latitude) // 중심 좌표의 위도 좌표
                        .build())

                .retrieve() //response 불러옴
                .toEntity(String.class)
                .block();
//        long end2 = System.currentTimeMillis();
//        System.out.println("용의자 시간 : " + (end2 - start2) / 1000.0);

        if (result.getBody() != null) {
            //받아온 JSON 데이터 가공
            //json parser
            JSONParser parser = new JSONParser();
            JSONObject object = (JSONObject)parser.parse(result.getBody());
            //searchPoiInfo의 value들
            JSONObject searchPoiInfo = (JSONObject) object.get("searchPoiInfo");
            //pois의 value들
            JSONObject pois = (JSONObject) searchPoiInfo.get("pois");
            //poi의 value는 배열이라 JSONArray 사용
            JSONArray poiArr = (JSONArray) pois.get("poi");

            List<FindDto> dtos = new ArrayList<>(); //리스트에 담을 dtos 선언
            List<ElevatorOrderDto> ele = new ArrayList<>();

            for (int i = 0; i < poiArr.size(); i++) {
                ElevatorOrderDto elevatorOrderDto = new ElevatorOrderDto();
                FindDto findDto = new FindDto();
                object = (JSONObject) poiArr.get(i);
                String middleAddrName = (String) object.get("middleAddrName"); // 도로명주소 ㅇㅇ로
                String roadName = (String) object.get("roadName"); // 도로명주소 ㅇㅇ로
                String firstBuildNo = (String) object.get("firstBuildNo"); //건물번호1
                findDto.setMiddleAddrName(middleAddrName);
                findDto.setRoadName(roadName);
                findDto.setFirstBuildNo(firstBuildNo);

                String addr = middleAddrName + " " + roadName + " " + firstBuildNo;

                String encodedAddr = URLEncoder.encode(addr, "UTF-8");

                elevatorOrderDto.setAddress(encodedAddr);
                elevatorOrderDto.setOrder(i);

                ele.add(i, elevatorOrderDto);
            }

            Map<Integer, String> elevatorResult = new HashMap<>();
//            long start1 = System.currentTimeMillis();
            elevatorResult = findElevatorByAPI(ele);
//            long end1 = System.currentTimeMillis();
//            System.out.println("엘레베이터만 걸리는 시간 : " + (end1 - start1) / 1000.0);

            //다시 poi의 value를 받아온 배열을 개수만큼 담기 (검색했을 때 출력하는 리스트 최대 10개)
            for (int i = 0; i < poiArr.size(); i++) {

                FindDto findDto = new FindDto();
                object = (JSONObject) poiArr.get(i);

                //이제 newAddress 안의 경도, 위도, 도로명 주소 쓰기 위해 또 파싱
                JSONObject newAddressList = (JSONObject) object.get("newAddressList");
                JSONArray newAddress = (JSONArray) newAddressList.get("newAddress");

                if (newAddress.size() != 0) {
                    JSONObject object1 = (JSONObject) newAddress.get(0);

                    //이제 필요한 애들 받아오기
                    String fullAddressRoad = (String) object1.get("fullAddressRoad"); //도로명 주소
                    String centerLat = (String) object1.get("centerLat"); //위도
                    String centerLon = (String) object1.get("centerLon"); //경도
                    String name = (String) object.get("name"); // 이름
                    String bizName = (String) object.get("bizName"); // 업종명
                    String upperBizName = (String) object.get("upperBizName"); //업종명 대분류
                    String middleAddrName = (String) object.get("middleAddrName"); // 도로명주소 ㅇㅇ로
                    String roadName = (String) object.get("roadName"); // 도로명주소 ㅇㅇ로
                    String firstBuildNo = (String) object.get("firstBuildNo"); //건물번호1

                    findDto.setName(name);
//                    findDto.setFullAddressRoad(fullAddressRoad);
                    findDto.setLatitude(Double.parseDouble(centerLat));
                    findDto.setLongitude(Double.parseDouble(centerLon));
                    findDto.setBizName(bizName);
                    findDto.setUpperBizName(upperBizName);
//                    findDto.setMiddleAddrName(middleAddrName);
//                    findDto.setRoadName(roadName);
//                    findDto.setFirstBuildNo(firstBuildNo);

                    switch (name) {
                        case "상명대학교 제1공학관":
                        case "상명대학교 미래백년관":
                        case "상명대학교 종합관":
                        case "상명대학교 생활예술관":
                            findDto.setFullAddressRoad("서울 종로구 홍지문2길 20");
                            findDto.setMiddleAddrName("종로구");
                            findDto.setRoadName("홍지문2길");
                            findDto.setFirstBuildNo("20");
                            findDto.setElevatorState("운행중");

                            dtos.add(i, findDto);

                            break;
                        case "상명대학교 디자인대학":
                            findDto.setFullAddressRoad("충남 천안시 동남구 상명대길 31");
                            findDto.setMiddleAddrName("동남구");
                            findDto.setRoadName("상명대길");
                            findDto.setFirstBuildNo("31");
                            findDto.setElevatorState("운행중");

                            dtos.add(i, findDto);
                            break;
                        default:
                            findDto.setFullAddressRoad(fullAddressRoad);
                            findDto.setMiddleAddrName(middleAddrName);
                            findDto.setRoadName(roadName);
                            findDto.setFirstBuildNo(firstBuildNo);

                            findDto.setElevatorState(elevatorResult.get(i));

                            dtos.add(i, findDto);
                            break;
                    }
//                String addr = middleAddrName + " " + roadName + " " + firstBuildNo;
//                    findDto.setElevatorState(elevatorResult.get(i));
//                    dtos.add(i, findDto);
                } else { //건물이 아니라 도로 같은거라서 [] 안에 비어있을 경우
                    String name = (String) object.get("name"); // 이름
                    String upperBizName = (String) object.get("upperBizName"); //업종명 대분류
                    String frontLat = (String) object.get("frontLat"); //위도
                    String frontLon = (String) object.get("frontLon"); //경도

                    findDto.setName(name);
                    findDto.setUpperBizName(upperBizName);
                    findDto.setLatitude(Double.parseDouble(frontLat));
                    findDto.setLongitude(Double.parseDouble(frontLon));

                    dtos.add(i, findDto);
                }
            }
//            long end0 = System.currentTimeMillis();
//            System.out.println("총 시간 : " + (end0 - start0) / 1000.0);
            return dtos;
        } else {
            return null;
        }
    }


    @SneakyThrows
    public Map<Integer, String> findElevatorByAPI(List<ElevatorOrderDto> ele) {

//        long start = System.currentTimeMillis();
        List<String> responseResult = fetchElevator(ele).collectSortedList(Comparator.reverseOrder()).block();
//        long end = System.currentTimeMillis();

        Map<Integer, String> result = new HashMap<Integer, String>();
        Map<String, String> map = new HashMap<>();

        for (int i = 0; i < ele.size(); i++) {

            org.json.JSONObject object = XML.toJSONObject(responseResult.get(i));

            if (!object.has("response")) { //호출 실패하면(아마 null일듯)
                findElevatorByAPI(ele); // 함수 다시 불러
                return null;
            }
            org.json.JSONObject response = (org.json.JSONObject) object.get("response");
            org.json.JSONObject body = (org.json.JSONObject) response.get("body");

            if (!(body.get("items").equals(""))) { // 엘리베이터가 없으면 body":{"items":"","numOfRows":,"pageNo":,"totalCount":} 이런식으로 반환
                org.json.JSONObject items = (org.json.JSONObject) body.get("items");
                //item value들
                org.json.JSONObject item = (org.json.JSONObject) items.get("item");
                //필요한 엘리베이터 정보 받아오기
                String elvtrSttsNm = (String) item.get("elvtrSttsNm");
                //return elvtrSttsNm;
                String addr = (String) item.get("address1");

                String[] t = addr.split(" ");
                List<String> str_list = new ArrayList<String>(Arrays.asList(t));

                str_list.remove(0);
                String s = str_list.get(0);
                String r = String.valueOf((s.charAt(s.length() - 1)));

                String result_str = String.join(" ", str_list);

                map.put(result_str, elvtrSttsNm);
            }
        }

        for (int i = 0; i < ele.size(); i++) {
            String decodedAddr = URLDecoder.decode(ele.get(i).getAddress());
            String elevator = map.get(decodedAddr);

            if (elevator == null) {
                result.put(ele.get(i).getOrder(), "x");
            } else {
                result.put(ele.get(i).getOrder(), elevator);
            }
        }
        return result;
    }

    //계단 api


    public List<StairDto> findStairs() throws IOException {

        File doc = new File(new File("./src/main/resources/stair.txt").getCanonicalPath());

        BufferedReader obj = new BufferedReader(new InputStreamReader(new FileInputStream(doc), "utf-8"));
        String[] Name;
        String str;
        String name;
        String lati;
        String longt;

        int j = 0;

        List<StairDto> dtos = new ArrayList<>();

        while ((str = obj.readLine()) != null) {
            Name = str.split("\\t");
            name = Name[0];
            lati = Name[1];
            longt = Name[2];

            StairDto stairDto = new StairDto();


            //일단 테스트로 이제 가공한 데이터를 stairDto에 저장

            stairDto.setRdnmadr(name);
            stairDto.setStartlatitude(Double.valueOf(lati));
            stairDto.setStartlongitude(Double.valueOf(longt));


            Pattern str_a = Pattern.compile("아파트");
            if (name == null) {
                dtos.add(j, stairDto);
                j += 1;
            } else {
                Matcher matcher = str_a.matcher(name);
                if (!matcher.find()) {
                    dtos.add(j, stairDto);
                    j += 1;
                }
            }

        }

        return dtos;

    }


    //계단 api 이전
    /*
    @SneakyThrows
    public List<StairDto> findStairs() {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders(); //헤더
        restTemplate.getMessageConverters().add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8)); // 한글깨짐 방지

        String encodedPath = URLEncoder.encode("인천시_이동약자연결시설_18종1", "UTF-8");
        //URI 생성

        UriComponents uri = UriComponentsBuilder
                .fromUriString(stair_url)
                .path("/" + encodedPath + "/FeatureServer/12/query")
                .queryParam("where", "1%3D1")
                .queryParam("outFields", "objectid,ctprvnnm,signgunm,signgucode,rdnmadr,lnmadr,startlatitude,startlongitude,endlatitude,endlongitude")
                .queryParam("outSR", 4326)
                .queryParam("f", "json")
                .build(true);

        //response
        ResponseEntity<String> result = restTemplate.exchange(uri.toUri(), HttpMethod.GET, new HttpEntity<String>(headers), String.class);

        JSONParser parser = new JSONParser();
        JSONObject object = (JSONObject)parser.parse(result.getBody());

        if(result.getBody()==null){
            for(int k=0;k<10;k++){
                result = restTemplate.exchange(uri.toUri(), HttpMethod.GET, new HttpEntity<String>(headers), String.class);

                parser = new JSONParser();
                object = (JSONObject)parser.parse(result.getBody());
                if(result.getBody() != null){
                    break;
                }
            }
        }

        if (result.getBody() != null) {
            JSONArray features = (JSONArray) object.get("features");

            List<StairDto> dtos = new ArrayList<>(); //리스트에 담을 dtos 선언
            int j = 0;

            //배열 크기만큼 반복
            for (int i = 0; i < features.size(); i++) {
                StairDto stairDto = new StairDto();
                object = (JSONObject) features.get(i);

                JSONObject attributes = (JSONObject) object.get("attributes");

                //이제 필요한 애들 받아오기
                Long objectid = (Long) attributes.get("objectid"); //id
                String ctprvnnm = (String)attributes.get("ctprvnnm"); //인천광역시
                String signgunm = (String)attributes.get("signgunm"); //ㅇㅇ구
                String signgucode = (String)attributes.get("signgucode"); //  우편번호
                String rdnmadr = (String) attributes.get("rdnmadr"); // 도로명주소
                String lnmadr = (String) attributes.get("lnmadr"); // 지명주소
                Double startlatitude = (Double) attributes.get("startlatitude"); // 시작위도
                Double startlongitude = (Double) attributes.get("startlongitude"); // 시작경도
                Double endlatitude = (Double) attributes.get("endlatitude"); //끝위도
                Double endlongitude = (Double) attributes.get("endlongitude"); //끝경도

                //일단 테스트로 이제 가공한 데이터를 stairDto에 저장
                stairDto.setObjectid(objectid);
                stairDto.setCtprvnnm(ctprvnnm);
                stairDto.setSigngucode(signgucode);
                stairDto.setSigngunm(signgunm);
                stairDto.setSigngunm(rdnmadr);
                stairDto.setSigngunm(lnmadr);
                stairDto.setStartlatitude(startlatitude);
                stairDto.setStartlongitude(startlongitude);
                stairDto.setEndlatitude(endlatitude);
                stairDto.setEndlongitude(endlongitude);

                Pattern str_a = Pattern.compile("아파트");
                if (rdnmadr == null) {
                    dtos.add(j, stairDto);
                    j += 1;
                } else {
                    Matcher matcher = str_a.matcher(rdnmadr);
                    if (!matcher.find()) {
                        dtos.add(j, stairDto);
                        j += 1;
                    }
                }
            }
            return dtos;
        }
        return  null;
    }

*/

    public List<ElevatorDto> findElevators() throws IOException {

        File doc = new File(new File("./src/main/resources/elevator.txt").getCanonicalPath());

        BufferedReader obj = new BufferedReader(new InputStreamReader(new FileInputStream(doc), "utf-8"));
        String[] Name;
        String str;
        String name;
        String lati;
        String longt;

        int j = 0;

        List<ElevatorDto> dtos = new ArrayList<>();

        while ((str = obj.readLine()) != null) {
            Name = str.split("\\t");
            name = Name[0];
            lati = Name[1];
            longt = Name[2];

            ElevatorDto elevatorDto = new ElevatorDto();


            //일단 테스트로 이제 가공한 데이터를 stairDto에 저장

            elevatorDto.setObjectid(Long.valueOf(j));
            elevatorDto.setLatitude(Double.valueOf(lati));
            elevatorDto.setLongitude(Double.valueOf(longt));

            dtos.add(j,elevatorDto);
            j += 1;


        }

        return dtos;

    }

    /*
    @SneakyThrows
    public List<ElevatorDto> findElevators() { // 엘리베이터 위도,경도(위치) 가져오는 api
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders(); //헤더
        restTemplate.getMessageConverters().add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8)); // 한글깨짐 방지

        String encodedPath = URLEncoder.encode("인천시_이동약자연결시설_18종1", "UTF-8");
        //URI 생성
        UriComponents uri = UriComponentsBuilder
                .fromUriString(stair_url)
                .path("/" + encodedPath + "/FeatureServer/6/query")
                .queryParam("where", "1%3D1")
                .queryParam("outFields", "objectid,latitude,longitude")
                .queryParam("outSR", 4326)
                .queryParam("f", "json")
                .build(true);

        //response
        ResponseEntity<String> result = restTemplate.exchange(uri.toUri(), HttpMethod.GET, new HttpEntity<String>(headers), String.class);

        if(result.getBody() != null) {
            //json parser
            JSONParser parser = new JSONParser();
            JSONObject object = (JSONObject)parser.parse(result.getBody());

            JSONArray features = (JSONArray) object.get("features");

            List<ElevatorDto> dtos = new ArrayList<>(); //리스트에 담을 dtos 선언

            //배열 크기만큼 반복
            for (int i = 0; i < features.size(); i++) {
                ElevatorDto elevatorDto = new ElevatorDto();
                object = (JSONObject) features.get(i);

                JSONObject attributes = (JSONObject) object.get("attributes");

                //이제 필요한 애들 받아오기
                Long objectid = (Long) attributes.get("objectid"); // id(개수 체크용)
                double latitude = (double) attributes.get("latitude"); //위도
                double longitude = (double) attributes.get("longitude"); //경도

                //일단 테스트로 이제 가공한 데이터를 elevatorDto에 저장
                elevatorDto.setObjectid(objectid);
                elevatorDto.setLatitude(latitude);
                elevatorDto.setLongitude(longitude);

                dtos.add(i, elevatorDto);
            }
            return dtos;
        }
        else {
            return null;
        }
    }
*/
    public Mono<String> getEle(ElevatorOrderDto ele) {

        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory("http://openapi.elevator.go.kr/openapi/service/ElevatorOperationService");
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);
        WebClient wc = WebClient.builder().uriBuilderFactory(factory).baseUrl("http://openapi.elevator.go.kr/openapi/service/ElevatorOperationService").build();

        //String encodedName = URLEncoder.encode(address,"UTF-8");

        return wc.get()
                .uri(uriBuilder -> uriBuilder.path("/getOperationInfoList")
                        .queryParam("serviceKey",elevator_apikey)
                        .queryParam("buld_address",ele.getAddress()) //모다 부평점
                        .queryParam("numOfRows",1) // 1개만 출력
                        .queryParam("pageNo",1).build())
                .retrieve().bodyToMono(String.class);
    }

    public ParallelFlux<String> fetchElevator(List<ElevatorOrderDto> adds) throws Exception{

        ParallelFlux<String> result = Flux.fromIterable(adds)
                //.sort((obj1,obj2)-> obj1.getOrder().compareTo(obj2.getOrder()))
                .parallel()
                .runOn(Schedulers.parallel())
                .flatMap(this::getEle);
        return result;
    }

    @SneakyThrows
    public String tMapReverseGeoCoding(String lat, String lon) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders(); //헤더
        restTemplate.getMessageConverters().add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8)); // 한글깨짐 방지

        //URI 생성
        UriComponents uri = UriComponentsBuilder
                .fromUriString(tmap_rg_url)
                .queryParam("lon", lon)
                .queryParam("lat", lat)
                .queryParam("version", 1)
                .queryParam("appKey", tmap_apiKey)
                .build(true);

        //response
        ResponseEntity<String> result = restTemplate.exchange(uri.toUri(), HttpMethod.GET, new HttpEntity<String>(headers), String.class);

        JSONParser parser = new JSONParser();
        JSONObject object = (JSONObject)parser.parse(result.getBody());
        JSONObject addressInfo = (JSONObject) object.get("addressInfo");

        return addressInfo.get("fullAddress").toString();
    }
}
