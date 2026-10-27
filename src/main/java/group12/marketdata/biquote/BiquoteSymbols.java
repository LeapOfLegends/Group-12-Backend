package group12.marketdata.biquote;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

final class BiquoteSymbols {

    private static final Map<String, String> PROVIDER_SYMBOLS = Map.of(
            "EUR/USD", "EURUSD",
            "USD/JPY", "USDJPY",
            "GBP/USD", "GBPUSD",
            "AUD/USD", "AUDUSD",
            "USD/CAD", "USDCAD",
            "USD/INR", "USDINR"
    );
    private static final Map<String, String> INTERNAL_SYMBOLS = PROVIDER_SYMBOLS.entrySet()
            .stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private BiquoteSymbols() {
    }

    static boolean supports(String internalSymbol) {
        return PROVIDER_SYMBOLS.containsKey(normalize(internalSymbol));
    }

    static String toProviderSymbol(String internalSymbol) {
        return PROVIDER_SYMBOLS.get(normalize(internalSymbol));
    }

    static String toInternalSymbol(String providerSymbol) {
        return INTERNAL_SYMBOLS.get(normalize(providerSymbol));
    }

    static String normalize(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
