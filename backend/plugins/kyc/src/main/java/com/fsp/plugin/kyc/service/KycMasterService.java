package com.fsp.plugin.kyc.service;

import com.fsp.plugin.kyc.api.KycDtos;
import com.fsp.plugin.kyc.repository.KycCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/** Reference data service — banks, entity types, MCCs, document types, countries,
 *  Namibian regions and bank account types. The plugin is the only source of these values. */
@Service
public class KycMasterService {

    private final KycCaseRepository caseRepository;

    // Static reference data mirroring core's kyc_master_values (plugin is independent)
    private static final Map<String, List<KycDtos.MasterValue>> MASTER_DATA = new LinkedHashMap<>();

    static {
        MASTER_DATA.put("BANK", List.of(
            new KycDtos.MasterValue("BANK", "BANK_WINDHOEK", "Bank Windhoek"),
            new KycDtos.MasterValue("BANK", "FNB_NAMIBIA", "First National Bank Namibia"),
            new KycDtos.MasterValue("BANK", "NEDBANK_NAMIBIA", "Nedbank Namibia"),
            new KycDtos.MasterValue("BANK", "STANDARD_BANK", "Standard Bank Namibia")
        ));
        MASTER_DATA.put("ENTITY_TYPE", List.of(
            new KycDtos.MasterValue("ENTITY_TYPE", "SOLE_PROPRIETORSHIP", "Sole Proprietorship"),
            new KycDtos.MasterValue("ENTITY_TYPE", "PARTNERSHIP", "Partnership"),
            new KycDtos.MasterValue("ENTITY_TYPE", "PRIVATE_LIMITED", "Private Limited / LLC"),
            new KycDtos.MasterValue("ENTITY_TYPE", "PUBLIC_CORPORATION", "Public Corporation"),
            new KycDtos.MasterValue("ENTITY_TYPE", "TRUST_NGO", "Trust / NGO")
        ));
        MASTER_DATA.put("MCC", List.of(
            new KycDtos.MasterValue("MCC", "5411", "Grocery Stores"),
            new KycDtos.MasterValue("MCC", "5812", "Restaurants"),
            new KycDtos.MasterValue("MCC", "5311", "Department Stores"),
            new KycDtos.MasterValue("MCC", "5734", "Computer Software"),
            new KycDtos.MasterValue("MCC", "8911", "Professional Services")
        ));
        MASTER_DATA.put("DOC_TYPE", List.of(
            new KycDtos.MasterValue("DOC_TYPE", "PASSPORT", "Passport"),
            new KycDtos.MasterValue("DOC_TYPE", "NATIONAL_ID", "National ID"),
            new KycDtos.MasterValue("DOC_TYPE", "DRIVERS_LICENSE", "Driver's License")
        ));
        MASTER_DATA.put("COUNTRY", List.of(
            new KycDtos.MasterValue("COUNTRY", "NA", "Namibia"),
            new KycDtos.MasterValue("COUNTRY", "ZA", "South Africa"),
            new KycDtos.MasterValue("COUNTRY", "BW", "Botswana"),
            new KycDtos.MasterValue("COUNTRY", "AO", "Angola"),
            new KycDtos.MasterValue("COUNTRY", "US", "United States")
        ));
        MASTER_DATA.put("REGION", java.util.stream.Stream.of(
                "Erongo", "Hardap", "Karas", "Kavango East", "Kavango West", "Khomas", "Kunene",
                "Ohangwena", "Omaheke", "Omusati", "Oshana", "Oshikoto", "Otjozondjupa", "Zambezi")
            .map(r -> new KycDtos.MasterValue("REGION", r.toUpperCase().replace(' ', '_'), r)).toList());
        MASTER_DATA.put("GENDER", List.of(
            new KycDtos.MasterValue("GENDER", "FEMALE", "Female"),
            new KycDtos.MasterValue("GENDER", "MALE", "Male"),
            new KycDtos.MasterValue("GENDER", "OTHER", "Other"),
            new KycDtos.MasterValue("GENDER", "UNDISCLOSED", "Prefer not to say")
        ));
        MASTER_DATA.put("ACCOUNT_TYPE", List.of(
            new KycDtos.MasterValue("ACCOUNT_TYPE", "SETTLEMENT", "Settlement"),
            new KycDtos.MasterValue("ACCOUNT_TYPE", "CURRENT", "Current / cheque"),
            new KycDtos.MasterValue("ACCOUNT_TYPE", "SAVINGS", "Savings")
        ));
    }

    /** Whether a code is in a master category (server-side validation of picklist fields). */
    public static boolean isValid(String category, String code) {
        return code != null && MASTER_DATA.getOrDefault(category, List.of()).stream().anyMatch(v -> v.code().equals(code));
    }

    public KycMasterService(KycCaseRepository caseRepository) {
        this.caseRepository = caseRepository;
    }

    @Transactional(readOnly = true)
    public KycDtos.MasterListResponse list(String category) {
        List<KycDtos.MasterValue> items = category == null || category.isBlank()
            ? MASTER_DATA.values().stream().flatMap(List::stream).toList()
            : MASTER_DATA.getOrDefault(category, List.of());
        return new KycDtos.MasterListResponse(items);
    }
}
