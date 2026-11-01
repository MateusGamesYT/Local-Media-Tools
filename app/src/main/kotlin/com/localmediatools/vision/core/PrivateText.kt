package com.localmediatools.vision.core

import java.text.Normalizer

/** Kinds of private details found in a line of text. */
enum class Detail {
    EMAIL, PHONE, CARD, ID_NUMBER, BANK, PASSWORD, NAME, ADDRESS, BIRTH_DATE, USERNAME, NUMBER
}

/**
 * What [PrivateText.classify] made of a line: its private [details]; whether it has a label such as
 * "Password" or "Name" ([label]); and whether it is only that label ([labelOnly]), so the value is
 * probably the text next to it or under it.
 */
class TextVerdict(val details: Set<Detail>, val label: Boolean, val labelOnly: Boolean) {
    val isPrivate get() = details.isNotEmpty()
}

/**
 * Recognises personal details in text read from a photo: e-mail addresses, phone numbers, card,
 * document and account numbers (with their check digits where there are any), passwords and codes,
 * names, addresses, dates of birth and user names, by their form and by the labels in front of them
 * (in the app's languages). Text read from photos has mistakes, so digits that look like letters
 * (O, I, l, S, B) count as digits inside mostly-numeric words.
 *
 * Names: [firstNames] and [surnames] are lower case without accents ([plain]). A first name
 * followed by another capitalised word is a name, unless the first name is also a common word on
 * signs ([COMMON_WORDS]); then the next word must be a known surname.
 */
class PrivateText(private val firstNames: Set<String>, private val surnames: Set<String>) {

    fun classify(raw: String): TextVerdict {
        val line = raw.trim()
        if (line.isEmpty()) return TextVerdict(emptySet(), false, false)
        val p = " " + plain(line).replace(Regex("\\s+"), " ") + " "
        val out = HashSet<Detail>()
        val labels = HashSet<Detail>()
        var rest = p
        for ((kind, words) in LABELS) {
            for (w in words) {
                if (has(p, w)) { labels.add(kind); rest = remove(rest, w) }
            }
        }
        val label = labels.isNotEmpty()
        val labelOnly = label && rest.count { it.isLetterOrDigit() } < 3
        // A label makes its line private (the value is on it or, for a label alone, next to it);
        // a phone label only with a number or alone ("Mobile Bar" is not a phone number).
        out.addAll(labels); if (!labelOnly) out.remove(Detail.PHONE)

        val fixed = fixDigits(line)
        if (EMAIL.containsMatchIn(fixed) || PROVIDERS.containsMatchIn(p)) out.add(Detail.EMAIL)
        else if (HANDLE.containsMatchIn(fixed)) out.add(Detail.USERNAME)
        if (iban(line) || iban(fixed)) out.add(Detail.BANK)
        else numbers(NOT_NUMBERS.replace(fixed, " ; "), out, Detail.PHONE in labels)
        if (CEP.containsMatchIn(fixed) || UK_POSTCODE.containsMatchIn(line) || US_ZIP.containsMatchIn(line)) out.add(Detail.ADDRESS)
        if (STREET.any { has(p, it) } && Regex("\\b\\d{1,5}[a-z]?\\b").containsMatchIn(p)) out.add(Detail.ADDRESS)
        if (hasName(line)) out.add(Detail.NAME)
        return TextVerdict(out, label, labelOnly)
    }

    /** Digit runs: card numbers (Luhn), document numbers by their shape, phone numbers, other long numbers. */
    private fun numbers(s: String, out: MutableSet<Detail>, phoneLabel: Boolean) {
        for (m in NUMBER_RUN.findAll(s)) {
            val run = m.value.trim()
            val digits = run.filter { it.isDigit() }
            if (digits.length < 6) continue
            val before = s.substring(maxOf(0, m.range.first - 4), m.range.first)
            if (MONEY_BEFORE.containsMatchIn(before) || MONEY.matches(run) || DATE.matches(run) || TIME.matches(run)) continue
            if (digits.length == 8 && run.length == 8 && compactDate(digits)) continue
            when {
                ID_SHAPES.any { it.matches(run) } -> out.add(Detail.ID_NUMBER)
                digits.length in 13..19 && (luhn(digits) || CARD_GROUPS.matches(run)) -> out.add(Detail.CARD)
                phoneLabel && digits.length >= 6 -> out.add(Detail.PHONE)
                // Without a label, a phone number is written in groups or with its country code.
                digits.length in 8..15 && run.length > digits.length && PHONE_SHAPE.matches(run) -> out.add(Detail.PHONE)
                digits.length >= 8 -> out.add(Detail.NUMBER)
            }
        }
    }

    private fun hasName(line: String): Boolean {
        val words = line.split(Regex("[\\s,;:/|]+")).map { it.trim('.', '(', ')', '"', '\'', '-') }.filter { it.isNotEmpty() }
        for (i in words.indices) {
            val w = words[i]
            if (w.length < 3 || !w[0].isUpperCase() || !w.all { it.isLetter() }) continue
            val first = plain(w)
            if (first !in firstNames) continue
            // The next capitalised word, skipping particles (Maria da Silva, Ludwig van Beethoven).
            var j = i + 1
            while (j < words.size && plain(words[j]) in PARTICLES) j++
            if (j >= words.size) continue
            val n = words[j]
            if (n.length < 2 || !n[0].isUpperCase() || !n.all { it.isLetter() || it == '\'' }) continue
            val next = plain(n)
            if (next in COMMON_WORDS || next in STREET_WORDS) continue
            if (first in COMMON_WORDS) { if (next in surnames) return true; continue }
            if (next in surnames || next in firstNames || n.length >= 3) return true
        }
        return false
    }

    companion object {
        /** Lower case, accents removed (é → e), for matching words. */
        fun plain(s: String): String {
            val d = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
            val b = StringBuilder(d.length)
            for (c in d) if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) b.append(c)
            return b.toString()
        }

        /** [w] as a whole word or phrase in [p] (which starts and ends with a space); CJK labels anywhere. */
        private fun has(p: String, w: String): Boolean {
            if (w.any { it.code >= 0x2E80 }) return p.contains(w)
            var i = p.indexOf(w)
            while (i >= 0) {
                val a = p.getOrNull(i - 1); val b = p.getOrNull(i + w.length)
                if ((a == null || !a.isLetterOrDigit()) && (b == null || !b.isLetterOrDigit())) return true
                i = p.indexOf(w, i + 1)
            }
            return false
        }

        private fun remove(p: String, w: String): String = if (w.any { it.code >= 0x2E80 }) p.replace(w, " ")
            else p.replace(Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(w) + "(?![\\p{L}\\p{N}])"), " ")

        /** Inside mostly-numeric words, letters next to a digit that OCR confuses with digits become digits. */
        fun fixDigits(s: String): String = s.split(' ').joinToString(" ") { t ->
            val alnum = t.count { it.isLetterOrDigit() }
            val dig = t.count { it.isDigit() }
            if (t.length >= 4 && alnum > 0 && dig * 2 > alnum && '@' !in t) t.mapIndexed { i, c ->
                val nearDigit = t.getOrNull(i - 1)?.isDigit() == true || t.getOrNull(i + 1)?.isDigit() == true
                if (!nearDigit) c
                else when (c) { 'O', 'o', 'D' -> '0'; 'I', 'l', '|', 'i' -> '1'; 'S', 's' -> '5'; 'B' -> '8'; 'Z', 'z' -> '2'; else -> c }
            }.joinToString("") else t
        }

        /** Eight digits that read as a date (DDMMYYYY or YYYYMMDD, years 1900–2099). */
        fun compactDate(d: String): Boolean {
            fun ok(day: Int, month: Int, year: Int) = day in 1..31 && month in 1..12 && year in 1900..2099
            val a = d.substring(0, 2).toInt(); val b = d.substring(2, 4).toInt(); val y = d.substring(4).toInt()
            return ok(a, b, y) || ok(b, a, y) || ok(d.substring(6).toInt(), d.substring(4, 6).toInt(), d.substring(0, 4).toInt())
        }

        fun luhn(d: String): Boolean {
            var sum = 0
            for ((k, c) in d.reversed().withIndex()) {
                var x = c - '0'
                if (k % 2 == 1) { x *= 2; if (x > 9) x -= 9 }
                sum += x
            }
            return sum % 10 == 0
        }

        /** An IBAN with a correct check (ISO 13616: mod 97 = 1). */
        fun iban(s: String): Boolean {
            for (m in IBAN.findAll(s.uppercase())) {
                val v = m.value.replace(" ", "")
                if (v.length !in 15..34) continue
                val r = v.substring(4) + v.substring(0, 4)
                var mod = 0
                var ok = true
                for (c in r) {
                    val n = when (c) { in '0'..'9' -> c - '0'; in 'A'..'Z' -> c - 'A' + 10; else -> { ok = false; 0 } }
                    mod = if (n >= 10) (mod * 100 + n) % 97 else (mod * 10 + n) % 97
                }
                if (ok && mod == 1) return true
            }
            return false
        }

        private val EMAIL = Regex("[A-Za-z0-9._%+-]+\\s?@\\s?[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\s?\\.\\s?[A-Za-z]{2,}")
        private val PROVIDERS = Regex("@ ?(gmail|hotmail|outlook|yahoo|icloud|live|uol|bol|terra|proton|gmx|web\\.de|qq|163|naver)\\b")
        private val HANDLE = Regex("(^|\\s)@[A-Za-z0-9_.]{3,}")
        private val IBAN = Regex("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{2,4}){3,8}\\b")
        private val NUMBER_RUN = Regex("\\+?\\(?\\d(?:[\\d]|[ .\\-/()]{1,2}(?=\\d))*\\d\\)?")
        private val MONEY_BEFORE = Regex("(R\\$|US\\$|\\$|€|£|¥|₩|₽|EUR|USD|BRL)\\s*$")
        private val MONEY = Regex("\\d{1,3}([.,]\\d{3})*[.,]\\d{2}")
        private val DATE = Regex("\\d{1,2}[/.\\-]\\d{1,2}[/.\\-](\\d{2}|\\d{4})|\\d{4}[/.\\-]\\d{1,2}[/.\\-]\\d{1,2}")
        private val TIME = Regex("\\d{1,2}[:.]\\d{2}([:.]\\d{2})?")
        /** Dates (also with spaces), times and spans of years: cut out before looking for numbers. */
        private val NOT_NUMBERS = Regex("(?<!\\d)(\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4}|\\d{4}[/.\\-]\\d{1,2}[/.\\-]\\d{1,2}|\\d{1,2} \\d{1,2} (19|20)\\d{2}|\\d{1,2}:\\d{2}(:\\d{2})?|(1[5-9]|20)\\d{2}( ?[-–] ?| )(1[5-9]|20)\\d{2})(?!\\d)")
        private val CARD_GROUPS = Regex("\\d{4}[ \\-]\\d{4}[ \\-]\\d{4}[ \\-]\\d{1,7}")
        private val PHONE_SHAPE = Regex("\\+?(\\(?\\d{1,4}\\)?[ .\\-]?){1,3}\\d{3,5}[ .\\-]?\\d{4}")
        private val ID_SHAPES = listOf(
            Regex("\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}"),           // Brazilian CPF
            Regex("\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}"),    // Brazilian CNPJ
            Regex("\\d{1,2}\\.\\d{3}\\.\\d{3}-[\\dX]"),         // Brazilian RG
            Regex("\\d{3}-\\d{2}-\\d{4}"),                       // US SSN
        )
        private val CEP = Regex("\\b\\d{5}-\\d{3}\\b")
        private val UK_POSTCODE = Regex("\\b[A-Z]{1,2}\\d[A-Z\\d]? \\d[A-Z]{2}\\b")
        private val US_ZIP = Regex("\\b(AL|AK|AZ|AR|CA|CO|CT|DE|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY|DC),? \\d{5}(-\\d{4})?\\b")

        /** Label words and phrases per kind, lower case without accents. */
        val LABELS: List<Pair<Detail, List<String>>> = listOf(
            Detail.PASSWORD to listOf("password", "passwort", "passwd", "pwd", "passcode", "senha", "contrasena", "mot de passe", "mdp",
                "kennwort", "wachtwoord", "parola d'ordine", "pin", "pin code", "codigo pin", "network key", "security key", "wifi key",
                "wi-fi key", "chave de seguranca", "cvv", "cvc", "cvv2", "otp", "verification code", "codigo de verificacao",
                "codigo de seguranca", "codigo de seguridad", "security code", "access code", "codigo de acesso", "codice",
                "パスワード", "暗証番号", "密码", "密碼", "口令", "비밀번호", "암호", "пароль"),
            Detail.USERNAME to listOf("username", "user name", "user id", "login", "usuario", "utilisateur", "identifiant", "benutzername",
                "nome de usuario", "nome utente", "ユーザー名", "用户名", "아이디", "логин"),
            Detail.NAME to listOf("name", "full name", "first name", "last name", "surname", "nome", "nome completo", "sobrenome", "nombre",
                "apellido", "apellidos", "nom", "prenom", "nom de famille", "vorname", "nachname", "cognome", "titular", "cardholder",
                "card holder", "passenger", "passageiro", "pasajero", "passager", "paciente", "patient", "signature", "assinatura",
                "unterschrift", "姓名", "氏名", "名前", "이름", "성명", "имя", "фамилия", "отчество"),
            Detail.ADDRESS to listOf("address", "endereco", "direccion", "domicilio", "adresse", "indirizzo", "anschrift", "cep", "zip code",
                "postcode", "post code", "postal code", "codigo postal", "code postal", "plz", "住所", "地址", "주소", "адрес"),
            Detail.ID_NUMBER to listOf("cpf", "rg", "cnpj", "cnh", "ssn", "social security", "passport", "passport no", "passaporte",
                "pasaporte", "passeport", "reisepass", "dni", "nif", "curp", "id no", "id number", "identidade", "identity card",
                "carteira de identidade", "documento", "document no", "licence no", "license no", "driver license", "driving licence",
                "matricula", "nis", "pis", "titulo de eleitor", "身份证", "マイナンバー", "주민등록번호", "паспорт", "инн", "снилс"),
            Detail.BANK to listOf("iban", "bic", "swift", "agencia", "conta corrente", "account no", "account number", "acct",
                "routing number", "sort code", "chave pix", "pix", "linha digitavel", "card number", "numero do cartao", "card no",
                "valid thru", "valid through", "validade", "expiry", "exp date", "銀行口座", "银行账号", "계좌번호", "номер счета"),
            Detail.BIRTH_DATE to listOf("dob", "d.o.b", "date of birth", "birth date", "birthdate", "born on", "nascimento", "data de nascimento",
                "fecha de nacimiento", "date de naissance", "ne le", "nee le", "geburtsdatum", "geboren", "data di nascita",
                "生年月日", "出生日期", "생년월일", "дата рождения"),
            Detail.PHONE to listOf("tel", "tel.", "telefone", "telephone", "phone", "fone", "cel", "celular", "cell", "mobile", "movil",
                "whatsapp", "fax", "telefono", "telefon", "handy", "電話", "电话", "手机", "전화", "휴대폰", "тел", "телефон"),
        )

        /** Street words: with a house number they make an address. */
        private val STREET = listOf("rua", "avenida", "av.", "travessa", "alameda", "rodovia", "estrada", "praca", "largo", "street", "st.",
            "avenue", "ave", "road", "rd", "boulevard", "blvd", "lane", "drive", "calle", "carrera", "camino", "paseo", "rue", "allee",
            "chemin", "impasse", "strasse", "str.", "platz", "gasse", "via", "viale", "piazza", "corso", "vicolo", "улица", "ул.",
            "проспект", "丁目", "番地", "路", "街", "号", "번지")
        private val STREET_WORDS = setOf("street", "avenue", "road", "lane", "drive", "boulevard", "rua", "avenida", "calle", "rue", "strasse")
        private val PARTICLES = setOf("da", "de", "do", "dos", "das", "del", "della", "di", "du", "van", "von", "der", "den", "le", "la", "y", "e", "bin", "ibn")

        /**
         * First names that are also everyday words, places or brands on signs: the ones found three
         * or more times in TextOCR's annotations of 2,724 Open Images photos (not the 400 the
         * measurements use), less the ones that are mostly people's names, and the like in the
         * app's other languages.
         */
        val COMMON_WORDS = setOf(
            "star", "london", "del", "king", "france", "river", "los", "america", "angel", "art", "may", "page", "will", "just",
            "india", "washington", "paris", "any", "justice", "april", "dream", "rio", "olive", "ginger", "hudson", "love", "royal",
            "come", "van", "june", "rose", "francisco", "tales", "candy", "summer", "august", "ray", "mac", "kit", "asia", "ford",
            "storm", "brain", "winter", "line", "lane", "stone", "cherry", "liberty", "diamond", "terra", "bud", "baby", "boston",
            "sol", "salvador", "angeles", "brandy", "georgia", "princess", "case", "brooklyn", "jewel", "forest", "easton", "navy",
            "ever", "virginia", "rex", "florida", "italia", "major", "guy", "amber", "tel", "marine", "journey", "miles", "dawn",
            "orion", "grant", "pearl", "christian", "sierra", "sunshine", "atlas", "rey", "german", "halo", "cash", "desire",
            "carolina", "hope", "grace", "faith", "joy", "glory", "honey", "sugar", "sky", "rain", "autumn", "spring", "rock",
            "cloud", "golden", "lucky", "sunny", "happy", "precious", "luz", "paz", "rosa", "flor", "mar", "lua", "vitoria",
            "gloria", "aurora", "estrela", "cruz", "pilar", "mercedes", "bela", "linda", "doce", "santos", "neves", "jesus",
            "victoria", "lincoln", "jordan", "madison", "chase", "hunter", "austin", "dallas", "houston", "phoenix", "denver",
            "camden", "chelsea", "kent", "sydney", "york", "jersey", "berlin", "milan", "roma", "vienna", "florence", "savannah",
            "nevada", "dakota", "montana", "arizona", "china", "japan", "kenya", "egypt", "brasil", "mexico", "eden", "noel",
            "christmas", "easter", "saint", "san", "santa", "sao", "don", "dom", "bay", "best", "big", "new", "old", "red",
            "blue", "green", "white", "black", "gold", "silver", "ruby", "jade", "ivory", "coral", "crystal", "ebony", "jasmine",
            "lily", "daisy", "violet", "iris", "ivy", "holly", "hazel", "heather", "laurel", "willow",
        )
    }
}
