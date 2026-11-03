package dev.overhaul.minecraft.smoke;

/** Small JSON string writer shared by the development receipt and fixture evidence. */
final class SmokeJson {
    private SmokeJson() { }
    static String quote(String value) {
        StringBuilder out=new StringBuilder("\"");
        for (int index=0;index<value.length();index++) {
            char character=value.charAt(index);
            switch (character) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (character<32) {
                        String hex=Integer.toHexString(character);
                        out.append("\\u");
                        for (int padding=hex.length();padding<4;padding++) out.append('0');
                        out.append(hex);
                    } else out.append(character);
            }
        }
        return out.append('"').toString();
    }
}
