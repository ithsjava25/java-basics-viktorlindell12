package com.example;

import com.example.api.ElpriserAPI;
import com.example.api.ElpriserAPI.Elpris;
import com.example.api.ElpriserAPI.Prisklass;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class Main {

    public static void main(String[] args) {
        ElpriserAPI api = new ElpriserAPI();

        Prisklass prisklass = null;   // Här sparas zonen (SE1, SE2, SE3, SE4)
        LocalDate datum = LocalDate.now();   // Standard är dagens datum
        boolean sortera = false;     // Om vi ska sortera priser eller inte
        int laddtimmar = 0;          // Antal timmar vi vill ladda

        // Om inget skrivs in → visa hjälptext och avsluta
        if (args.length == 0) {
            printUsage();
            return;
        }

        // Kolla igenom vad användaren har skrivit in i kommandot
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--zone" -> {
                    // Här väljer man zon (obligatoriskt)
                    if (i + 1 < args.length) {
                        try {
                            prisklass = Prisklass.valueOf(args[++i].toUpperCase());
                        } catch (IllegalArgumentException e) {
                            System.out.println("Ogiltig zon: " + args[i]);
                            return;
                        }
                    } else {
                        System.out.println("--zone är obligatoriskt");
                        return;
                    }
                }
                case "--date" -> {
                    // Här kan man välja datum (annars blir det idag)
                    if (i + 1 < args.length) {
                        try {
                            datum = LocalDate.parse(args[++i]);
                        } catch (Exception e) {
                            System.out.println("Ogiltigt datumformat (YYYY-MM-DD krävs)");
                            return;
                        }
                    }
                }
                case "--sort", "--sorted" -> sortera = true;   // Ska vi sortera priserna?
                case "--hours", "--charging" -> {
                    // Här kan man skriva in antal laddtimmar (t.ex. 4h)
                    if (i + 1 < args.length) {
                        String val = args[++i];
                        if (val.endsWith("h")) val = val.substring(0, val.length() - 1);
                        try {
                            laddtimmar = Integer.parseInt(val);
                        } catch (NumberFormatException e) {
                            System.out.println("Ogiltigt värde för --hours: " + args[i]);
                            return;
                        }
                    }
                }
                case "--help" -> {
                    // Om man skriver --help visas hjälptexten
                    printUsage();
                    return;
                }
            }
        }

        // Om ingen zon valts → avbryt
        if (prisklass == null) {
            System.out.println("--zone är obligatoriskt");
            return;
        }

        // Hämta priser för idag + imorgon
        List<Elpris> priser = new ArrayList<>(api.getPriser(datum, prisklass));
        priser.addAll(api.getPriser(datum.plusDays(1), prisklass));

        LocalDateTime nu = LocalDateTime.now();

        // Om klockan är efter 13 idag → ta bara med framtida timmar + morgondagen
        if (datum.equals(LocalDate.now()) && nu.toLocalTime().isAfter(LocalTime.of(13, 0))) {
            priser = priser.stream()
                    .filter(p -> !p.timeStart().toLocalDateTime().isBefore(nu))
                    .collect(Collectors.toList());
        }

        if (priser.isEmpty()) {
            System.out.println("Inga priser hittades för " + prisklass + " " + datum);
            return;
        }

        List<Elpris> priserFörVisning = new ArrayList<>(priser);

        // --- Sortering av priser ---
        if (sortera) {
            // Sortera: först efter prisnivå (högsta → lägsta), sedan tid
            priserFörVisning.sort(
                    Comparator.comparingDouble(Elpris::sekPerKWh).reversed()
                            .thenComparing(p -> p.timeStart().toLocalDateTime())
            );
        } else {
            // Om vi inte sorterar → visa bara i tidsordning
            priserFörVisning.sort(Comparator.comparing(Elpris::timeStart));
        }



        // --- Skriva ut alla priser ---
        System.out.printf("Elpriser för %s %s (%d timmar):\n",
                prisklass, datum, priserFörVisning.size());

        for (Elpris pris : priserFörVisning) {
            System.out.printf("%s %s öre\n",
                    formatHourRange(pris.timeStart().toLocalDateTime(), pris.timeEnd().toLocalDateTime()),
                    formatOere(pris.sekPerKWh()));
        }

        // --- Statistik: min, max, medel ---
        Map<Integer, List<Elpris>> priserPerTimme = priser.stream()
                .collect(Collectors.groupingBy(p -> p.timeStart().toLocalDateTime().getHour()));

        double min = priserPerTimme.values().stream()
                .mapToDouble(l -> l.stream().mapToDouble(Elpris::sekPerKWh).average().orElse(Double.MAX_VALUE))
                .min()
                .orElse(0) * 100;

        double max = priserPerTimme.values().stream()
                .mapToDouble(l -> l.stream().mapToDouble(Elpris::sekPerKWh).average().orElse(Double.MIN_VALUE))
                .max()
                .orElse(0) * 100;

        double avg = priser.stream()
                .mapToDouble(Elpris::sekPerKWh)
                .average()
                .orElse(0) * 100;

        int minHour = priserPerTimme.entrySet().stream()
                .min(Comparator.comparingDouble(e -> e.getValue().stream().mapToDouble(Elpris::sekPerKWh).average().orElse(Double.MAX_VALUE)))
                .map(Map.Entry::getKey).orElse(0);

        int maxHour = priserPerTimme.entrySet().stream()
                .max(Comparator.comparingDouble(e -> e.getValue().stream().mapToDouble(Elpris::sekPerKWh).average().orElse(Double.MIN_VALUE)))
                .map(Map.Entry::getKey).orElse(0);

        System.out.printf("Lägsta pris: %s öre (%02d-%02d)\n", formatOereValue(min), minHour, (minHour + 1) % 24);
        System.out.printf("Högsta pris: %s öre (%02d-%02d)\n", formatOereValue(max), maxHour, (maxHour + 1) % 24);
        System.out.printf("Medelpris: %s öre\n", formatOereValue(avg));

        // --- Tips om laddning ---
        if (laddtimmar > 0 && laddtimmar <= priser.size()) {
            System.out.printf("\nPåbörja laddning under de %d billigaste timmarna:\n", laddtimmar);

            List<Elpris> laddtider = findOptimalChargingBlock(priser, laddtimmar);

            for (Elpris pris : laddtider) {
                System.out.printf("kl %s %s öre\n",
                        formatClock(pris.timeStart().toLocalDateTime()),
                        formatOere(pris.sekPerKWh()));
            }

            double snitt = laddtider.stream()
                    .mapToDouble(Elpris::sekPerKWh)
                    .average()
                    .orElse(0) * 100;

            System.out.printf("Medelpris för fönster: %s öre\n", formatOereValue(snitt));
        }
    }

    // Hjälptext för hur man kör programmet
    private static void printUsage() {
        System.out.println("Usage: java Main --zone <SE1|SE2|SE3|SE4> [options]");
        System.out.println("Options:");
        System.out.println("  --zone      Elområde (obligatoriskt)");
        System.out.println("  --date      Datum i format YYYY-MM-DD (default: idag)");
        System.out.println("  --sort, --sorted   Sortera priser stigande");
        System.out.println("  --hours, --charging Antal timmar att ladda (t.ex. 4h)");
        System.out.println("  --help      Visa denna hjälptext");
    }

    // Formatera tidsintervall (exempel: "20-21")
    private static String formatHourRange(LocalDateTime start, LocalDateTime end) {
        int startHour = start.getHour();
        int endHour = end.getHour();
        return String.format("%02d-%02d", startHour, endHour % 24);
    }

    // Visa tiden som HH:mm
    private static String formatClock(LocalDateTime time) {
        return time.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    // Gör om pris till öre
    private static String formatOere(double sekPerKWh) {
        return formatOereValue(sekPerKWh * 100);
    }

    // Visa pris i öre med två decimaler
    private static String formatOereValue(double value) {
        return String.format(Locale.forLanguageTag("sv-SE"), "%.2f", value).replace('.', ',');
    }

    // Hitta de billigaste timmarna i följd för laddning
    private static List<Elpris> findOptimalChargingBlock(List<Elpris> priser, int hours) {
        if (priser.size() < hours) return Collections.emptyList();

        // Sortera priser i tidsordning
        List<Elpris> sorted = new ArrayList<>(priser);
        sorted.sort(Comparator.comparing(Elpris::timeStart));

        // Gör en lista som går runt (så vi kan räkna över midnatt)
        List<Elpris> circular = new ArrayList<>(sorted);
        circular.addAll(sorted);

        double minSum = Double.MAX_VALUE;
        int bestStartIndex = 0;

        // Gå igenom alla block av timmar och hitta det billigaste
        for (int i = 0; i < sorted.size(); i++) {
            double sum = 0;
            for (int j = 0; j < hours; j++) {
                sum += circular.get(i + j).sekPerKWh();
            }
            if (sum < minSum) {
                minSum = sum;
                bestStartIndex = i;
            }
        }

        // Returnera det billigaste blocket
        return circular.subList(bestStartIndex, bestStartIndex + hours);

    }
}




