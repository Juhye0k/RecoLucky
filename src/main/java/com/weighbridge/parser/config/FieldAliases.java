package com.weighbridge.parser.config;

import java.util.List;
import java.util.Map;

/**
 * 라벨 별칭 사전. 새 양식 추가 시 이 클래스만 수정하면 확장 가능.
 *
 * <ul>
 *   <li>{@link #FIELD_ALIASES} — 일반 필드 별칭 (계량일자, 차량번호, 거래처 등)</li>
 *   <li>{@link #WEIGHT_ALIASES} — 중량 필드 별칭 (총중량, 공차중량, 실중량)</li>
 *   <li>{@link #DOC_TYPE_ALIASES} — 문서 유형 별칭 (계량증명서, 계근표 등)</li>
 * </ul>
 */
public final class FieldAliases {

    public static final Map<String, List<String>> FIELD_ALIASES = Map.of(
        "measurement_date", List.of("계량일자", "날짜", "일시"),
        "vehicle_number", List.of("차량번호", "차번호", "차량No"),
        "customer", List.of("거래처", "상호", "회사명"),
        "product_name", List.of("품명"),
        "category", List.of("구분")
    );

    public static final Map<String, List<String>> WEIGHT_ALIASES = Map.of(
        "gross_weight", List.of("총중량"),
        "tare_weight", List.of("공차중량", "차중량"),
        "net_weight", List.of("실중량")
    );

    public static final List<String> DOC_TYPE_ALIASES = List.of(
        "계량증명서", "계근표", "계량확인서", "계량증명표"
    );

    private FieldAliases() {}
}
