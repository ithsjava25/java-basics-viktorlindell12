package com.example;

// Importerar klasser som behövs från vårt API (som hämtar elpriser)
import com.example.api.ElpriserAPI;
import com.example.api.ElpriserAPI.Elpris;
import com.example.api.ElpriserAPI.Prisklass;

// Importerar standardbibliotek för tid, datum och listor
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Main {

    public static void main(String[] args) {
        // Skapar en API-klient som kan hämta elpriser från ElpriserAPI
        ElpriserAPI api = new ElpriserAPI();

        // Variabler för att spara inställningar från användaren
        Prisklass prisklass = null;        // Elområde (t.ex. SE1, SE2, SE3, SE4)
        LocalDate datum = LocalDate.now(); // Standard: dagens datum
        boolean sortera = false;           // Ska priser sorteras?
        int laddtimmar = 0;                // Hur många timmar man vill ladda bilen

        // Om användaren inte skriver några argument → visa hjälptext
        if (args.length == 0) {
            printUsage();
            return;
        }

        // Läser in argument som användaren skriver i terminalen
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--zone")) {
                // Exempel: --zone SE3 (vilket elområde)
                if (i + 1 < args.length) {
                    try {
                        prisklass = Prisklass.valueOf(args[++i].toUpperCase());
                    } catch (Exception e) {
                        System.out.println("Ogiltig zon: " + args[i]);
                        return;
                    }
                } else {
                    System.out.println("--zone är obligatoriskt");
                    return;
                }
            } else if (args[i].equals("--date")) {
                // Exempel: --date 2025-10-05
                if (i + 1 < args.length) {
                    try {
                        datum = LocalDate.parse(args[++i]);
                    } catch (Exception e) {
                        System.out.println("Ogiltigt datumformat (YYYY-MM-DD krävs)");
                        return;
                    }
                }
            } else if (args[i].equals("--sort") || args[i].equals("--sorted")) {
                sortera = true; // Sortera priser om användaren vill
            } else if (args[i].equals("--hours") || args[i].equals("--charging")) {
                // Exempel: --hours 4h (hur länge man vill ladda bilen)
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
            } else if (args[i].equals("--help")) {
                // Om användaren ber om hjälp → visa instruktioner
                printUsage();
                return;
            }
        }

        // Om användaren glömt välja elområde → avbryt
        if (prisklass == null) {
            System.out.println("--zone är obligatoriskt");
            return;
        }

        // Hämta priser för både idag och imorgon
        List<Elpris> priser = new ArrayList<>(api.getPriser(datum, prisklass));
        priser.addAll(api.getPriser(datum.plusDays(1), prisklass));

        LocalDateTime nu = LocalDateTime.now();

        // Ta bort gamla timmar (t.ex. om klockan är 14:00 → visa bara framtida timmar)
        if (datum.equals(LocalDate.now()) && nu.toLocalTime().isAfter(LocalTime.of(13, 0))) {
            List<Elpris> framtid = new ArrayList<>();
            for (Elpris p : priser) {
                if (!p.timeStart().toLocalDateTime().isBefore(nu)) {
                    framtid.add(p);
                }
            }
            priser = framtid;
        }

        // Om inga priser hittades → avbryt
        if (priser.isEmpty()) {
            System.out.println("Inga priser hittades för " + prisklass + " " + datum);
            return;
        }

        // Kopiera priserna till en lista vi kan sortera
        List<Elpris> priserFörVisning = new ArrayList<>(priser);

        // Sortera listan antingen på pris eller tid
        if (sortera) {
            // Sortera på pris (lägsta först, om lika → tid)
            for (int i = 0; i < priserFörVisning.size(); i++) {
                for (int j = i + 1; j < priserFörVisning.size(); j++) {
                    if (priserFörVisning.get(i).sekPerKWh() < priserFörVisning.get(j).sekPerKWh()) {
                        Elpris temp = priserFörVisning.get(i);
                        priserFörVisning.set(i, priserFörVisning.get(j));
                        priserFörVisning.set(j, temp);
                    } else if (priserFörVisning.get(i).sekPerKWh() == priserFörVisning.get(j).sekPerKWh()) {
                        if (priserFörVisning.get(i).timeStart().isAfter(priserFörVisning.get(j).timeStart())) {
                            Elpris temp = priserFörVisning.get(i);
                            priserFörVisning.set(i, priserFörVisning.get(j));
                            priserFörVisning.set(j, temp);
                        }
                    }
                }
            }
        } else {
            // Sortera på tid (tidigast först)
            for (int i = 0; i < priserFörVisning.size(); i++) {
                for (int j = i + 1; j < priserFörVisning.size(); j++) {
                    if (priserFörVisning.get(i).timeStart().isAfter(priserFörVisning.get(j).timeStart())) {
                        Elpris temp = priserFörVisning.get(i);
                        priserFörVisning.set(i, priserFörVisning.get(j));
                        priserFörVisning.set(j, temp);
                    }
                }
            }
        }

        // Skriv ut priserna
        System.out.printf("Elpriser för %s %s (%d timmar):\n", prisklass, datum, priserFörVisning.size());
        for (Elpris pris : priserFörVisning) {
            System.out.printf("%s %s öre\n",
                    formatHourRange(pris.timeStart().toLocalDateTime(), pris.timeEnd().toLocalDateTime()),
                    formatOere(pris.sekPerKWh()));
        }

        // Räkna ut statistik: min, max, medel
        Map<Integer, List<Elpris>> priserPerTimme = new HashMap<>();
        for (Elpris p : priser) {
            int timme = p.timeStart().toLocalDateTime().getHour();
            priserPerTimme.putIfAbsent(timme, new ArrayList<>());
            priserPerTimme.get(timme).add(p);
        }

        double min = 999999;
        double max = -1;
        double total = 0;
        int count = 0;
        int minHour = 0, maxHour = 0;

        for (Map.Entry<Integer, List<Elpris>> entry : priserPerTimme.entrySet()) {
            double summa = 0;
            for (Elpris p : entry.getValue()) {
                summa += p.sekPerKWh();
            }
            double medel = summa / entry.getValue().size();
            if (medel < min) {
                min = medel;
                minHour = entry.getKey();
            }
            if (medel > max) {
                max = medel;
                maxHour = entry.getKey();
            }
            total += summa;
            count += entry.getValue().size();
        }

        double avg = (count > 0) ? total / count : 0;
        min *= 100; max *= 100; avg *= 100;

        // Skriv ut statistik
        System.out.printf("Lägsta pris: %s öre (%02d-%02d)\n", formatOereValue(min), minHour, (minHour + 1) % 24);
        System.out.printf("Högsta pris: %s öre (%02d-%02d)\n", formatOereValue(max), maxHour, (maxHour + 1) % 24);
        System.out.printf("Medelpris: %s öre\n", formatOereValue(avg));

        // Ge tips om laddning om användaren angav timmar (--hours)
        if (laddtimmar > 0 && laddtimmar <= priser.size()) {
            System.out.printf("\nPåbörja laddning under de %d billigaste timmarna:\n", laddtimmar);
            List<Elpris> laddtider = findOptimalChargingBlock(priser, laddtimmar);
            for (Elpris pris : laddtider) {
                System.out.printf("kl %s %s öre\n",
                        formatClock(pris.timeStart().toLocalDateTime()),
                        formatOere(pris.sekPerKWh()));
            }
            double snitt = 0;
            for (Elpris p : laddtider) snitt += p.sekPerKWh();
            snitt = (snitt / laddtider.size()) * 100;
            System.out.printf("Medelpris för fönster: %s öre\n", formatOereValue(snitt));
        }
    }

    // Hjälptext som visas om användaren kör "--help"
    private static void printUsage() {
        System.out.println("Usage: java Main --zone <SE1|SE2|SE3|SE4> [options]");
        System.out.println("Options:");
        System.out.println("  --zone      Elområde");
        System.out.println("  --date      Datum i format YYYY-MM-DD (default: idag)");
        System.out.println("  --sort, --sorted   Sortera priser stigande");
        System.out.println("  --hours, --charging Antal timmar att ladda");
        System.out.println("  --help      Visa denna hjälptext");
    }

    // Hjälpmetoder för att formatera utskrift
    private static String formatHourRange(LocalDateTime start, LocalDateTime end) {
        return String.format("%02d-%02d", start.getHour(), end.getHour() % 24);
    }

    private static String formatClock(LocalDateTime time) {
        return time.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static String formatOere(double sekPerKWh) {
        return formatOereValue(sekPerKWh * 100);
    }

    private static String formatOereValue(double value) {
        return String.format(Locale.forLanguageTag("sv-SE"), "%.2f", value).replace('.', ',');
    }

    // Metod för att hitta billigaste sammanhängande timmar att ladda
    private static List<Elpris> findOptimalChargingBlock(List<Elpris> priser, int hours) {
        if (priser.size() < hours) return Collections.emptyList();

        // Sortera priser i tidsordning
        List<Elpris> sorted = new ArrayList<>(priser);
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                if (sorted.get(i).timeStart().isAfter(sorted.get(j).timeStart())) {
                    Elpris temp = sorted.get(i);
                    sorted.set(i, sorted.get(j));
                    sorted.set(j, temp);
                }
            }
        }

        // Kopiera listan två gånger (för att hantera övergång från en dag till nästa)
        List<Elpris> circular = new ArrayList<>(sorted);
        circular.addAll(sorted);

        // Leta efter den billigaste följden av "hours" antal timmar
        double minSum = Double.MAX_VALUE;
        int bestStartIndex = 0;

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






