package com.harry.renthouse.service.search.impl;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.harry.renthouse.base.ApiResponseEnum;
import com.harry.renthouse.base.HouseSortOrderByEnum;
import com.harry.renthouse.elastic.entity.BaiduMapLocation;
import com.harry.renthouse.elastic.entity.HouseElastic;
import com.harry.renthouse.elastic.entity.HouseKafkaMessage;
import com.harry.renthouse.elastic.entity.HouseSuggestion;
import com.harry.renthouse.elastic.key.HouseElasticKey;
import com.harry.renthouse.elastic.repository.HouseElasticRepository;
import com.harry.renthouse.entity.House;
import com.harry.renthouse.entity.HouseDetail;
import com.harry.renthouse.entity.HouseTag;
import com.harry.renthouse.entity.SupportAddress;
import com.harry.renthouse.exception.BusinessException;
import com.harry.renthouse.repository.HouseDetailRepository;
import com.harry.renthouse.repository.HouseRepository;
import com.harry.renthouse.repository.HouseTagRepository;
import com.harry.renthouse.repository.SupportAddressRepository;
import com.harry.renthouse.service.ServiceMultiResult;
import com.harry.renthouse.service.house.AddressService;
import com.harry.renthouse.service.search.HouseElasticSearchService;
import com.harry.renthouse.web.dto.HouseBucketDTO;
import com.harry.renthouse.web.form.MapBoundSearchForm;
import com.harry.renthouse.web.form.SearchHouseForm;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.ParsedStringTerms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.search.suggest.Suggest;
import org.elasticsearch.search.suggest.SuggestBuilder;
import org.elasticsearch.search.suggest.SuggestBuilders;
import org.elasticsearch.search.suggest.completion.CompletionSuggestion;
import org.elasticsearch.search.suggest.completion.CompletionSuggestionBuilder;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.aggregation.AggregatedPage;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author admin
 * @date 2020/5/20 13:35
 */
@Service
@Slf4j
public class HouseElasticSearchServiceImpl implements HouseElasticSearchService {

    private static final String HOUSE_INDEX_TOPIC = "HOUSE_INDEX_TOPIC";

    private static final int DEFAULT_SUGGEST_SIZE = 5;

    @Value("${qiniu.cdnPrefix}")
    private String cdnPrefix;

    @Resource
    private HouseElasticRepository houseElasticRepository;

    @Resource
    private HouseRepository houseRepository;

    @Resource
    private HouseDetailRepository houseDetailRepository;

    @Resource
    private ModelMapper modelMapper;

    @Resource
    private HouseTagRepository houseTagRepository;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @Resource
    private Gson gson;

    @Resource
    private ElasticsearchRestTemplate elasticsearchRestTemplate;

    @Resource
    private SupportAddressRepository supportAddressRepository;

    @Resource
    private AddressService addressService;

    @KafkaListener(topics = {HOUSE_INDEX_TOPIC})
    public void handleMessage(String message){
        try{
            HouseKafkaMessage houseKafkaMessage = gson.fromJson(message, HouseKafkaMessage.class);
            switch (houseKafkaMessage.getOperation()){
                case HouseKafkaMessage.INDEX:
                    kafkaSave(houseKafkaMessage);
                    break;
                case HouseKafkaMessage.DELETE:
                    kafkaDelete(houseKafkaMessage);
                    break;
                default:
            }
        }catch (JsonSyntaxException e){
            log.error("?????????????????????: {}", message, e);
        }
    }

    private void kafkaSave(HouseKafkaMessage houseKafkaMessage){
        Long houseId = houseKafkaMessage.getId();
        HouseElastic houseElastic = new HouseElastic();
        House house = houseRepository.findById(houseId).orElseThrow(() -> new BusinessException(ApiResponseEnum.HOUSE_NOT_FOUND_ERROR));
        // ??????????????????
        modelMapper.map(house, houseElastic);
        // ??????????????????
        HouseDetail houseDetail = houseDetailRepository.findByHouseId(houseId).orElseThrow(() -> new BusinessException(ApiResponseEnum.HOUSE_DETAIL_NOT_FOUND_ERROR));
        modelMapper.map(houseDetail, houseElastic);
        // ??????????????????
        List<HouseTag> tags = houseTagRepository.findAllByHouseId(houseId);
        List<String> tagList = tags.stream().map(HouseTag::getName).collect(Collectors.toList());
        houseElastic.setTags(tagList);
        // ???????????????
        updateSuggests(houseElastic);
        // ???????????????
        SupportAddress city = supportAddressRepository.findByEnNameAndLevel(houseElastic.getCityEnName(), SupportAddress.AddressLevel.CITY.getValue())
                .orElseThrow(() -> new BusinessException(ApiResponseEnum.ADDRESS_CITY_NOT_FOUND));
        SupportAddress region = supportAddressRepository.findByBelongToAndEnNameAndLevel(houseElastic.getCityEnName(), houseElastic.getRegionEnName(), SupportAddress.AddressLevel.REGION.getValue())
                .orElseThrow(() -> new BusinessException(ApiResponseEnum.ADDRESS_REGION_NOT_FOUND));
        String address = city.getCnName() + region.getCnName() + houseElastic.getAddress();
        BaiduMapLocation location = addressService.getBaiduMapLocation(city.getCnName(), address).orElse(null);
        houseElastic.setLocation(location);
        // ?????????elastic???
        log.debug(houseElastic.toString());
        houseElasticRepository.save(houseElastic);
        // ??????poi??????, (???????????????poi????????????)
        /*String lbsTitle = house.getStreet() + house.getDistrict();
        String lbsAddress = city.getCnName() + region.getCnName() + house.getStreet() + house.getDistrict();
        String cover = cdnPrefix + house.getCover();
        addressService.lbsUpload(houseElastic.getLocation(),
                lbsTitle, lbsAddress, houseId, houseElastic.getPrice(), houseElastic.getArea(), cover);*/
    }

    private void updateSuggests(HouseElastic houseElastic){
        // ????????????????????????
        // todo ?????????????????????????????????
        /*AnalyzeRequestBuilder analyzeRequestBuilder = new AnalyzeRequestBuilder(
                elasticsearchClient,
                AnalyzeAction.INSTANCE, INDEX_NAME,
                houseElastic.getTitle(), houseElastic.getLayoutDesc(),
                houseElastic.getRoundService(),
                houseElastic.getDescription());
        analyzeRequestBuilder.setAnalyzer(IK_SMART);
        // ??????????????????
        AnalyzeResponse response = analyzeRequestBuilder.get();
        List<AnalyzeResponse.AnalyzeToken> tokens = response.getTokens();
        if(tokens == null){
            log.warn("??????????????????????????????????????????:" + houseElastic);
            throw new BusinessException(ApiResponseEnum.ELASTIC_HOUSE_SUGGEST_CREATE_ERROR);
        }
        List<HouseSuggestion> suggestionList = tokens.stream().filter(token -> !StringUtils.equals("<NUM>", token.getType())
                && StringUtils.isNotBlank(token.getTerm()) && token.getTerm().length() > 2).map(item -> {
            HouseSuggestion houseSuggestion = new HouseSuggestion();
            houseSuggestion.setInput(item.getTerm());
            return houseSuggestion;
        }).collect(Collectors.toList());
        log.debug("????????????------------------------");*/
        List<HouseSuggestion> suggestionList = new ArrayList<>();
        suggestionList.add(new HouseSuggestion(houseElastic.getTitle(), 30));
        suggestionList.add(new HouseSuggestion(houseElastic.getDistrict(), 20));
        if(StringUtils.isNotBlank(houseElastic.getSubwayLineName())){
            suggestionList.add(new HouseSuggestion(houseElastic.getSubwayLineName(), 15));
        }
        if(StringUtils.isNotBlank(houseElastic.getSubwayStationName())){
            suggestionList.add(new HouseSuggestion(houseElastic.getSubwayStationName(), 15));
        }
        houseElastic.setSuggests(suggestionList);
    }


    private void kafkaDelete(HouseKafkaMessage houseKafkaMessage){
        Long houseId = houseKafkaMessage.getId();
        houseElasticRepository.findById(houseId).orElseThrow(() -> new BusinessException(ApiResponseEnum.ELASTIC_HOUSE_NOT_FOUND));
        houseElasticRepository.deleteById(houseId);
        // ??????POI??????
        addressService.lbsRemove(houseId);
    }

    @Override
    public void save(Long houseId) {
        HouseKafkaMessage houseKafkaMessage = new HouseKafkaMessage(houseId, HouseKafkaMessage.INDEX, 0);
        kafkaTemplate.send(HOUSE_INDEX_TOPIC, gson.toJson(houseKafkaMessage));
    }

    @Override
    public void delete(Long houseId) {
        HouseKafkaMessage houseKafkaMessage = new HouseKafkaMessage(houseId, HouseKafkaMessage.DELETE, 0);
        kafkaTemplate.send(HOUSE_INDEX_TOPIC, gson.toJson(houseKafkaMessage));
    }

    @Override
    public ServiceMultiResult<HouseElastic> search(SearchHouseForm searchHouseForm) {
        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        boolQueryBuilder.filter(QueryBuilders.termQuery(HouseElasticKey.CITY_EN_NAME, searchHouseForm.getCityEnName()));
        // ???????????????
        if(StringUtils.isNotBlank(searchHouseForm.getKeyword())){
            boolQueryBuilder.must(QueryBuilders.multiMatchQuery(searchHouseForm.getKeyword(),
                    HouseElasticKey.TITLE,
                    HouseElasticKey.TRAFFIC,
                    HouseElasticKey.DISTRICT,
                    HouseElasticKey.ROUND_SERVICE,
                    HouseElasticKey.SUBWAY_LINE_NAME,
                    HouseElasticKey.SUBWAY_STATION_NAME
            ));
        }
        // ????????????
        if(StringUtils.isNotBlank(searchHouseForm.getRegionEnName())){
            boolQueryBuilder.filter(QueryBuilders.termQuery(HouseElasticKey.REGION_EN_NAME, searchHouseForm.getRegionEnName()));
        }
        // ????????????
        if(searchHouseForm.getSubwayLineId() != null){
            boolQueryBuilder.filter(QueryBuilders.termQuery(HouseElasticKey.SUBWAY_LINE_ID, searchHouseForm.getSubwayLineId()));
        }
        // ???????????????
        if(!CollectionUtils.isEmpty(searchHouseForm.getSubwayStationIdList())){
            BoolQueryBuilder inBuilder = QueryBuilders.boolQuery();
            searchHouseForm.getSubwayStationIdList().forEach((id) -> {
                inBuilder.should(QueryBuilders.termQuery(HouseElasticKey.SUBWAY_STATION_ID, id));
            });
            boolQueryBuilder.must(inBuilder);
        }
        // ????????????
        if(searchHouseForm.getDistanceSearch() != null){
            boolQueryBuilder.filter(QueryBuilders.geoDistanceQuery(HouseElasticKey.LOCATION)
                    .distance(searchHouseForm.getDistanceSearch().getDistance(), DistanceUnit.KILOMETERS)
                    .point(searchHouseForm.getDistanceSearch().getLat(), searchHouseForm.getDistanceSearch().getLon())
                    .boost(2));
        }
        // ????????????
        if(searchHouseForm.getRentWay() != null && searchHouseForm.getRentWay() >= 0){
            boolQueryBuilder.filter(QueryBuilders.termQuery(HouseElasticKey.RENT_WAY, searchHouseForm.getRentWay()));
        }
        // ??????????????????
        if(searchHouseForm.getAreaMin() != null || searchHouseForm.getAreaMax() != null){
            RangeQueryBuilder rangeQueryBuilder = QueryBuilders.rangeQuery(HouseElasticKey.AREA);
            if(searchHouseForm.getAreaMin() > 0){
                rangeQueryBuilder.gte(searchHouseForm.getAreaMin());
            }
            if(searchHouseForm.getAreaMax() > 0){
                rangeQueryBuilder.lte(searchHouseForm.getAreaMax());
            }
        }
        // ??????????????????
        if(searchHouseForm.getPriceMin() != null || searchHouseForm.getPriceMax() != null){
            RangeQueryBuilder rangeQueryBuilder = QueryBuilders.rangeQuery(HouseElasticKey.PRICE);
            if(searchHouseForm.getPriceMin() != null && searchHouseForm.getPriceMin() > 0){
                rangeQueryBuilder.gte(searchHouseForm.getPriceMin());
            }
            if(searchHouseForm.getPriceMax() != null && searchHouseForm.getPriceMax() > 0){
                rangeQueryBuilder.lte(searchHouseForm.getPriceMax());
            }
            boolQueryBuilder.filter(rangeQueryBuilder);
        }
        // ??????????????????
        if(!CollectionUtils.isEmpty(searchHouseForm.getTags())){
            BoolQueryBuilder inBuilder = QueryBuilders.boolQuery();
            searchHouseForm.getTags().forEach(tag -> {
                inBuilder.must(QueryBuilders.termQuery(HouseElasticKey.TAGS, tag));
            });
            boolQueryBuilder.must(inBuilder);
        }
        // ????????????
        if(searchHouseForm.getDirection() != null && searchHouseForm.getDirection() > 0){
            boolQueryBuilder.filter(QueryBuilders.termQuery(HouseElasticKey.DIRECTION, searchHouseForm.getDirection()));
        }

        // ????????????
        if(searchHouseForm.getBounds() != null){
            MapBoundSearchForm bounds = searchHouseForm.getBounds();
            boolQueryBuilder.filter(QueryBuilders.geoBoundingBoxQuery(HouseElasticKey.LOCATION)
                    .setCorners(new GeoPoint(bounds.getLeftTopLatitude(), bounds.getLeftTopLongitude()),
                            new GeoPoint(bounds.getRightBottomLatitude(), bounds.getRightBottomLongitude()))
            );
        }

        queryBuilder.withQuery(boolQueryBuilder);
        queryBuilder.withSort(SortBuilders.fieldSort(HouseSortOrderByEnum
                .from(searchHouseForm.getOrderBy())
                .orElse(HouseSortOrderByEnum.DEFAULT).getValue())
                .order(SortOrder.fromString(searchHouseForm.getSortDirection())));
        Pageable pageable = PageRequest.of(searchHouseForm.getPage() - 1, searchHouseForm.getPageSize());
        queryBuilder.withPageable(pageable);
        Page<HouseElastic> page = houseElasticRepository.search(queryBuilder.build());
        int total = (int) page.getTotalElements();
        return new ServiceMultiResult<>(total, page.getContent());
    }

    @Override
    public ServiceMultiResult<String> suggest(String prefix) {
        return suggest(prefix, DEFAULT_SUGGEST_SIZE);
    }

    @Override
    public ServiceMultiResult<String> suggest(String prefix, int size) {
        // ??????????????????
        CompletionSuggestionBuilder suggestionBuilder = SuggestBuilders.completionSuggestion(HouseElasticKey.SUGGESTS)
                .prefix(prefix).size(size);
        SuggestBuilder suggestBuilders = new SuggestBuilder();
        suggestBuilders.addSuggestion("autoComplete", suggestionBuilder);
        // ????????????????????????
        SearchResponse response = elasticsearchRestTemplate.suggest(suggestBuilders, HouseElastic.class);
        Suggest suggest = response.getSuggest();
        Suggest.Suggestion result = suggest.getSuggestion("autoComplete");

        // ?????????????????????
        Set<String> suggestSet = new HashSet<>();
        for (Object term : result.getEntries()) {
            if(term instanceof CompletionSuggestion.Entry){
                CompletionSuggestion.Entry item = (CompletionSuggestion.Entry) term;
                // ??????option?????????
                if(!CollectionUtils.isEmpty(item.getOptions())){
                    for (CompletionSuggestion.Entry.Option option : item.getOptions()) {
                        String tip = option.getText().string();
                        suggestSet.add(tip);
                        if(suggestSet.size() >= size){
                            break;
                        }
                    }
                }
            }
            if(suggestSet.size() >= size){
                break;
            }
        }
        List<String> list = Arrays.asList(suggestSet.toArray(new String[0]));
        return new ServiceMultiResult<>(list.size(), list);
    }

    @Override
    public int aggregateDistrictHouse(String cityEnName, String regionEnName, String district) {
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        // ?????????????????????
        boolQueryBuilder.filter( QueryBuilders.termQuery(HouseElasticKey.CITY_EN_NAME, cityEnName))
                .filter(QueryBuilders.termQuery(HouseElasticKey.REGION_EN_NAME, regionEnName))
                .filter(QueryBuilders.termQuery(HouseElasticKey.DISTRICT, district))
        ;
        NativeSearchQueryBuilder nativeSearchQueryBuilder = new NativeSearchQueryBuilder();
        nativeSearchQueryBuilder.withQuery(boolQueryBuilder);
        /*// ??????????????????
        nativeSearchQueryBuilder.addAggregation(AggregationBuilders.terms(HouseElasticKey.AGGS_DISTRICT_HOUSE)
        .field(HouseElasticKey.DISTRICT));
        // ???????????????????????????
        nativeSearchQueryBuilder.withSourceFilter(new FetchSourceFilter(new String[]{}, null));

        AggregatedPage<HouseElastic> houseAggPage = (AggregatedPage<HouseElastic>)houseElasticRepository.search(nativeSearchQueryBuilder.build());

        ParsedStringTerms houseTerm =(ParsedStringTerms) houseAggPage.getAggregation(HouseElasticKey.AGGS_DISTRICT_HOUSE);

        List<? extends Terms.Bucket> buckets = houseTerm.getBuckets();*/
        NativeSearchQuery query = nativeSearchQueryBuilder.build();
        log.debug(query.getQuery().toString());
        Page<HouseElastic> result = houseElasticRepository.search(query);
        return result.getSize();
    }

    @Override
    public Optional<HouseElastic> getByHouseId(Long houseId){
        return houseElasticRepository.findById(houseId);
    }

    @Override
    public ServiceMultiResult<HouseBucketDTO> mapAggregateRegionsHouse(String cityEnName) {
        // ???????????????????????????
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        boolQueryBuilder.filter(QueryBuilders.termQuery(HouseElasticKey.CITY_EN_NAME, cityEnName));
        NativeSearchQueryBuilder nativeSearchQueryBuilder = new NativeSearchQueryBuilder();
        nativeSearchQueryBuilder.withQuery(boolQueryBuilder);

        // ????????????????????????
        nativeSearchQueryBuilder.addAggregation(AggregationBuilders.terms(HouseElasticKey.AGG_REGION_HOUSE)
                .field(HouseElasticKey.REGION_EN_NAME));
        // ???????????????????????????
//        nativeSearchQueryBuilder.withSourceFilter(new FetchSourceFilter(new String[]{}, null));

        NativeSearchQuery query = nativeSearchQueryBuilder.build();

        log.debug(query.getQuery().toString());
        log.debug(query.getAggregations().toString());
        Page<HouseElastic> response = houseElasticRepository.search(query);

        AggregatedPage<HouseElastic> aggResult = (AggregatedPage<HouseElastic>)response;

        ParsedStringTerms houseTerm =(ParsedStringTerms) aggResult.getAggregation(HouseElasticKey.AGG_REGION_HOUSE);

        List<? extends Terms.Bucket> termsBuckets = houseTerm.getBuckets();

        List<HouseBucketDTO> houseBucketDTOS = termsBuckets.stream().map(item -> new HouseBucketDTO(((Terms.Bucket) item).getKeyAsString(), ((Terms.Bucket) item).getDocCount()))
                .collect(Collectors.toList());

        return new ServiceMultiResult<>(aggResult.getSize(), houseBucketDTOS);
    }
}
