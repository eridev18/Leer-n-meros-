public record Producto(String codigo, String nombre, double precio) {

    @Override
    public String toString() {
        return String.format("%s  |  %s  |  $%.2f", codigo, nombre, precio);
    }
}
