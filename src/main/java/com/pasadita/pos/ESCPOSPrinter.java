package com.pasadita.pos;

import com.pasadita.pos.dto.SaleDetailDTO;
import com.pasadita.pos.dto.TicketDTO;

import javax.print.*;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Clase para enviar comandos ESC/POS a impresoras térmicas Epson.
 * Soporta dos modos de impresión según el sistema operativo:
 * - Linux: Escritura directa al dispositivo (/dev/usb/lpX)
 * - Windows: Usa PrintService de Java con el nombre de impresora instalada
 * Compatible con: Epson TM-T88V y modelos similares.
 */
public class ESCPOSPrinter {

    // Ruta por defecto del dispositivo de impresora (Linux)
    private static final String DEFAULT_PRINTER_PATH = "/dev/usb/lp0";

    // Detección del sistema operativo
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("windows");

    // Comandos ESC/POS básicos
    private static final byte[] INIT = {0x1B, 0x40};                          // Inicializar impresora
    private static final byte[] CUT_PAPER = {0x1D, 0x56, 0x00};               // Cortar papel completo
    private static final byte[] CUT_PAPER_PARTIAL = {0x1D, 0x56, 0x01};       // Cortar papel parcial
    private static final byte[] CUT_PAPER_FEED = {0x1D, 0x56, 0x42, 0x03};    // Cortar con avance
    private static final byte[] LINE_FEED = {0x0A};                            // Nueva línea
    private static final byte[] ALIGN_LEFT = {0x1B, 0x61, 0x00};              // Alinear izquierda
    private static final byte[] ALIGN_CENTER = {0x1B, 0x61, 0x01};            // Alinear centro
    private static final byte[] ALIGN_RIGHT = {0x1B, 0x61, 0x02};             // Alinear derecha
    private static final byte[] BOLD_ON = {0x1B, 0x45, 0x01};                 // Negrita activada
    private static final byte[] BOLD_OFF = {0x1B, 0x45, 0x00};                // Negrita desactivada
    private static final byte[] DOUBLE_HEIGHT_ON = {0x1B, 0x21, 0x10};        // Doble altura
    private static final byte[] DOUBLE_WIDTH_ON = {0x1B, 0x21, 0x20};         // Doble ancho
    private static final byte[] DOUBLE_SIZE_ON = {0x1B, 0x21, 0x30};          // Doble tamaño (alto y ancho)
    private static final byte[] NORMAL_SIZE = {0x1B, 0x21, 0x00};             // Tamaño normal
    private static final byte[] UNDERLINE_ON = {0x1B, 0x2D, 0x01};            // Subrayado activado
    private static final byte[] UNDERLINE_OFF = {0x1B, 0x2D, 0x00};           // Subrayado desactivada
    private static final byte[] CHARSET_PC850 = {0x1B, 0x74, 0x02};           // Página de códigos PC850 (español)

    // Configuración
    private static final int TICKET_WIDTH = 42; // Caracteres por línea (típico para 80mm TM-T88V)
    private static final String SEPARATOR = "------------------------------------------";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final String businessName;
    private final String businessAddress;
    private final String businessPhone;
    private final String printerPath;      // Para Linux (/dev/usb/lpX)
    private final String printerName;      // Para Windows (nombre de impresora instalada)

    /**
     * Constructor completo con configuración del negocio y configuración de impresora.
     *
     * @param businessName    Nombre del negocio
     * @param businessAddress Dirección del negocio
     * @param businessPhone   Teléfono del negocio
     * @param printerPath     Ruta del dispositivo de impresora para Linux (ej: /dev/usb/lp5)
     * @param printerName     Nombre de impresora instalada para Windows (ej: "EPSON TM-T88V Receipt")
     */
    public ESCPOSPrinter(String businessName, String businessAddress, String businessPhone,
                         String printerPath, String printerName) {
        this.businessName = businessName;
        this.businessAddress = businessAddress;
        this.businessPhone = businessPhone;
        this.printerPath = printerPath != null ? printerPath : DEFAULT_PRINTER_PATH;
        this.printerName = printerName;
    }

    /**
     * Constructor con configuración del negocio y ruta de impresora (compatibilidad).
     * Para Windows, intentará usar el printerPath como nombre de impresora si no es un path de Linux.
     *
     * @param businessName    Nombre del negocio
     * @param businessAddress Dirección del negocio
     * @param businessPhone   Teléfono del negocio
     * @param printerPath     Ruta del dispositivo de impresora (ej: /dev/usb/lp5)
     */
    public ESCPOSPrinter(String businessName, String businessAddress, String businessPhone, String printerPath) {
        this(businessName, businessAddress, businessPhone, printerPath, null);
    }

    /**
     * Constructor con configuración del negocio (usa ruta de impresora por defecto).
     *
     * @param businessName    Nombre del negocio
     * @param businessAddress Dirección del negocio
     * @param businessPhone   Teléfono del negocio
     */
    public ESCPOSPrinter(String businessName, String businessAddress, String businessPhone) {
        this(businessName, businessAddress, businessPhone, DEFAULT_PRINTER_PATH, null);
    }

    /**
     * Constructor simple con valores por defecto.
     */
    public ESCPOSPrinter() {
        this("LA PASADITA", "", "", DEFAULT_PRINTER_PATH, null);
    }

    /**
     * Imprime un ticket completo.
     * En Linux: escribe directamente al dispositivo (/dev/usb/lpX)
     * En Windows: usa PrintService con el nombre de impresora instalada
     *
     * @param ticket DTO del ticket a imprimir
     * @throws PrinterException Si hay error al imprimir
     * @throws IOException      Si hay error al generar los datos
     */
    public void print(TicketDTO ticket) throws PrinterException, IOException {
        System.out.println("Generando comandos ESC/POS...");

        // 1. Generar comandos ESC/POS
        byte[] commands = generateESCPOS(ticket);

        // 2. Imprimir según el sistema operativo
        if (IS_WINDOWS) {
            printToWindowsPrinter(commands, "Ticket #" + ticket.getId());
        } else {
            printToLinuxDevice(commands, "Ticket #" + ticket.getId());
        }
    }

    /**
     * Imprime datos a un dispositivo de impresora en Linux.
     *
     * @param data        Bytes a enviar a la impresora
     * @param description Descripción del trabajo de impresión
     * @throws PrinterException Si hay error al imprimir
     */
    private void printToLinuxDevice(byte[] data, String description) throws PrinterException {
        System.out.println("Escribiendo a impresora (Linux): " + printerPath);

        try (FileOutputStream fos = new FileOutputStream(printerPath)) {
            fos.write(data);
            fos.flush();
            System.out.println("[OK] " + description + " enviado a impresora correctamente");
        } catch (IOException e) {
            System.err.println("[ERROR] Error escribiendo a impresora: " + e.getMessage());
            throw new PrinterException("No se pudo escribir en " + printerPath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Imprime datos usando PrintService de Java en Windows.
     *
     * @param data        Bytes a enviar a la impresora
     * @param description Descripción del trabajo de impresión
     * @throws PrinterException Si hay error al imprimir
     */
    private void printToWindowsPrinter(byte[] data, String description) throws PrinterException {
        String effectivePrinterName = getEffectivePrinterName();
        System.out.println("Imprimiendo a impresora (Windows): " + effectivePrinterName);

        PrintService printService = findPrintService(effectivePrinterName);
        if (printService == null) {
            throw new PrinterException("Impresora no encontrada: " + effectivePrinterName +
                    ". Impresoras disponibles: " + getAvailablePrinters());
        }

        try {
            DocPrintJob printJob = printService.createPrintJob();
            DocFlavor flavor = DocFlavor.BYTE_ARRAY.AUTOSENSE;
            Doc doc = new SimpleDoc(data, flavor, null);
            PrintRequestAttributeSet attributes = new HashPrintRequestAttributeSet();

            printJob.print(doc, attributes);
            System.out.println("[OK] " + description + " enviado a impresora correctamente");
        } catch (javax.print.PrintException e) {
            System.err.println("[ERROR] Error de impresión: " + e.getMessage());
            throw new PrinterException("Error al imprimir en " + effectivePrinterName + ": " + e.getMessage(), e);
        }
    }

    /**
     * Obtiene el nombre efectivo de la impresora para Windows.
     * Prioriza printerName, luego usa printerPath si no parece ser un path de Linux.
     */
    private String getEffectivePrinterName() {
        if (printerName != null && !printerName.isEmpty()) {
            return printerName;
        }
        // Si printerPath no es un path de Linux, usarlo como nombre de impresora
        if (printerPath != null && !printerPath.startsWith("/dev/")) {
            return printerPath;
        }
        return null;
    }

    /**
     * Busca un PrintService por nombre de impresora.
     *
     * @param printerName Nombre de la impresora a buscar
     * @return PrintService encontrado o null si no existe
     */
    private PrintService findPrintService(String printerName) {
        if (printerName == null) {
            return null;
        }
        PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        for (PrintService service : services) {
            if (service.getName().equalsIgnoreCase(printerName) ||
                    service.getName().toLowerCase().contains(printerName.toLowerCase())) {
                return service;
            }
        }
        return null;
    }

    /**
     * Obtiene lista de impresoras disponibles en el sistema.
     *
     * @return String con nombres de impresoras separados por coma
     */
    public String getAvailablePrinters() {
        PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < services.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(services[i].getName());
        }
        return sb.toString();
    }

    /**
     * Verifica si el ticket corresponde a un pedido a domicilio.
     * Un pedido a domicilio tiene deliveryOrderId y deliveryAddress NO nulos.
     *
     * @param ticket DTO del ticket
     * @return true si es pedido a domicilio, false si es venta en caja
     */
    private boolean isDeliveryOrder(TicketDTO ticket) {
        return ticket.getDeliveryOrderId() != null && ticket.getDeliveryAddress() != null
                && !ticket.getDeliveryAddress().isEmpty();
    }

    /**
     * Genera los comandos ESC/POS para un ticket completo.
     * Detecta automáticamente si es venta en caja o pedido a domicilio.
     *
     * @param ticket DTO del ticket
     * @return Array de bytes con los comandos ESC/POS
     * @throws IOException Si hay error al generar los datos
     */
    public byte[] generateESCPOS(TicketDTO ticket) throws IOException {
        boolean isDelivery = isDeliveryOrder(ticket);

        if (isDelivery) {
            return generateDeliveryTicket(ticket);
        } else {
            return generateCashRegisterTicket(ticket);
        }
    }

    /**
     * Genera ticket para venta en caja (punto de venta).
     *
     * @param ticket DTO del ticket
     * @return Array de bytes con los comandos ESC/POS
     * @throws IOException Si hay error al generar los datos
     */
    private byte[] generateCashRegisterTicket(TicketDTO ticket) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        // Inicializar impresora
        buffer.write(INIT);

        // Configurar página de códigos para español
        buffer.write(CHARSET_PC850);

        // Encabezado del negocio
        writeHeader(buffer);

        // Información del ticket (venta en caja)
        writeTicketInfoCashRegister(buffer, ticket);

        // Separador
        buffer.write(SEPARATOR.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);

        // Detalles de productos
        writeDetails(buffer, ticket.getSaleDetails());

        // Separador
        buffer.write(SEPARATOR.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);

        // Totales
        writeTotals(buffer, ticket);

        // Pie de ticket (venta en caja)
        writeFooterCashRegister(buffer, ticket);

        // Avance y corte de papel
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);
        buffer.write(CUT_PAPER_FEED);

        return buffer.toByteArray();
    }

    /**
     * Genera ticket para pedido a domicilio (delivery).
     *
     * @param ticket DTO del ticket
     * @return Array de bytes con los comandos ESC/POS
     * @throws IOException Si hay error al generar los datos
     */
    private byte[] generateDeliveryTicket(TicketDTO ticket) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        // Inicializar impresora
        buffer.write(INIT);

        // Configurar página de códigos para español
        buffer.write(CHARSET_PC850);

        // Encabezado del negocio
        writeHeader(buffer);

        // Banner de PEDIDO A DOMICILIO
        buffer.write(ALIGN_CENTER);
        buffer.write(BOLD_ON);
        buffer.write(DOUBLE_SIZE_ON);
        buffer.write("PEDIDO A DOMICILIO".getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(NORMAL_SIZE);
        buffer.write(BOLD_OFF);
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);

        // Información del pedido a domicilio
        writeTicketInfoDelivery(buffer, ticket);

        // Separador
        buffer.write(SEPARATOR.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);

        // Detalles de productos
        writeDetails(buffer, ticket.getSaleDetails());

        // Separador
        buffer.write(SEPARATOR.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);

        // Totales
        writeTotals(buffer, ticket);

        // Pie de ticket (delivery)
        writeFooterDelivery(buffer, ticket);

        // Avance y corte de papel
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);
        buffer.write(CUT_PAPER_FEED);

        return buffer.toByteArray();
    }

    /**
     * Escribe la información del ticket para venta en caja.
     */
    private void writeTicketInfoCashRegister(ByteArrayOutputStream buffer, TicketDTO ticket) throws IOException {
        buffer.write(ALIGN_LEFT);

        // Número de ticket
        buffer.write(BOLD_ON);
        buffer.write(("TICKET #" + ticket.getId()).getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(BOLD_OFF);
        buffer.write(LINE_FEED);

        // Fecha y hora
        if (ticket.getDatetime() != null) {
            buffer.write(("Fecha: " + ticket.getDatetime().format(DATE_FORMAT)).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        // Cajero
        if (ticket.getEmployeeName() != null) {
            buffer.write(("Cajero: " + ticket.getEmployeeName()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        // Cliente (opcional en caja)
        if (ticket.getCustomerName() != null && !ticket.getCustomerName().isEmpty()) {
            buffer.write(("Cliente: " + ticket.getCustomerName()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        // Método de pago
        if (ticket.getPaymentMethodName() != null) {
            buffer.write(("Pago: " + ticket.getPaymentMethodName()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        buffer.write(LINE_FEED);
    }

    /**
     * Escribe la información del ticket para pedido a domicilio.
     */
    private void writeTicketInfoDelivery(ByteArrayOutputStream buffer, TicketDTO ticket) throws IOException {
        buffer.write(ALIGN_LEFT);

        // Número de pedido (delivery order)
        buffer.write(BOLD_ON);
        buffer.write(DOUBLE_HEIGHT_ON);
        buffer.write(("PEDIDO #" + ticket.getDeliveryOrderId()).getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(NORMAL_SIZE);
        buffer.write(BOLD_OFF);
        buffer.write(LINE_FEED);

        // Número de ticket/venta asociado
        buffer.write(("Ticket Venta: #" + ticket.getId()).getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);

        // Fecha y hora
        if (ticket.getDatetime() != null) {
            buffer.write(("Fecha: " + ticket.getDatetime().format(DATE_FORMAT)).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        buffer.write(LINE_FEED);

        // Sección de datos del cliente (importante para delivery)
        buffer.write(BOLD_ON);
        buffer.write("------ DATOS DEL CLIENTE ------".getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(BOLD_OFF);
        buffer.write(LINE_FEED);

        // Nombre del cliente
        if (ticket.getCustomerName() != null && !ticket.getCustomerName().isEmpty()) {
            buffer.write(("Nombre: " + ticket.getCustomerName()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        // Teléfono del cliente
        if (ticket.getCustomerPhone() != null && !ticket.getCustomerPhone().isEmpty()) {
            buffer.write(("Telefono: " + ticket.getCustomerPhone()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        // Dirección de entrega (muy importante para delivery)
        if (ticket.getDeliveryAddress() != null && !ticket.getDeliveryAddress().isEmpty()) {
            buffer.write(BOLD_ON);
            buffer.write("Direccion:".getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(BOLD_OFF);
            buffer.write(LINE_FEED);
            // La dirección puede ser larga, escribirla en líneas separadas si es necesario
            writeWrappedText(buffer, ticket.getDeliveryAddress());
        }

        buffer.write(LINE_FEED);

        // Empleado que tomó el pedido
        if (ticket.getEmployeeName() != null) {
            buffer.write(("Atendio: " + ticket.getEmployeeName()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        // Método de pago
        if (ticket.getPaymentMethodName() != null) {
            buffer.write(("Forma de Pago: " + ticket.getPaymentMethodName()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        buffer.write(LINE_FEED);
    }

    /**
     * Escribe texto largo dividido en múltiples líneas.
     */
    private void writeWrappedText(ByteArrayOutputStream buffer, String text) throws IOException {
        if (text == null || text.isEmpty()) return;

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + ESCPOSPrinter.TICKET_WIDTH, text.length());
            buffer.write(text.substring(start, end).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
            start = end;
        }
    }

    /**
     * Escribe el pie del ticket para venta en caja.
     */
    private void writeFooterCashRegister(ByteArrayOutputStream buffer, TicketDTO ticket) throws IOException {
        buffer.write(LINE_FEED);
        buffer.write(ALIGN_CENTER);

        // Estado de pago
        if (ticket.isPaid()) {
            buffer.write(BOLD_ON);
            buffer.write("*** PAGADO ***".getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(BOLD_OFF);
        } else {
            buffer.write("** PENDIENTE DE PAGO **".getBytes(StandardCharsets.ISO_8859_1));
        }
        buffer.write(LINE_FEED);

        // Notas
        if (ticket.getNotes() != null && !ticket.getNotes().isEmpty()) {
            buffer.write(LINE_FEED);
            buffer.write(("Nota: " + ticket.getNotes()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        buffer.write(LINE_FEED);
        buffer.write("Gracias por su compra!".getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write(ALIGN_LEFT);
    }

    /**
     * Escribe el pie del ticket para pedido a domicilio.
     */
    private void writeFooterDelivery(ByteArrayOutputStream buffer, TicketDTO ticket) throws IOException {
        buffer.write(LINE_FEED);
        buffer.write(ALIGN_CENTER);

        // Estado de pago (importante para delivery - cobrar o no)
        if (ticket.isPaid()) {
            buffer.write(BOLD_ON);
            buffer.write(DOUBLE_SIZE_ON);
            buffer.write("*** PAGADO ***".getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(NORMAL_SIZE);
            buffer.write(BOLD_OFF);
        } else {
            buffer.write(BOLD_ON);
            buffer.write(DOUBLE_SIZE_ON);
            buffer.write("** COBRAR AL ENTREGAR **".getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(NORMAL_SIZE);
            buffer.write(BOLD_OFF);
            buffer.write(LINE_FEED);
            buffer.write(("Monto a cobrar: $" + formatPrice(ticket.getTotal())).getBytes(StandardCharsets.ISO_8859_1));
        }
        buffer.write(LINE_FEED);

        // Notas (pueden tener instrucciones de entrega)
        if (ticket.getNotes() != null && !ticket.getNotes().isEmpty()) {
            buffer.write(LINE_FEED);
            buffer.write(BOLD_ON);
            buffer.write("NOTAS:".getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(BOLD_OFF);
            buffer.write(LINE_FEED);
            writeWrappedText(buffer, ticket.getNotes());
        }

        buffer.write(LINE_FEED);
        buffer.write(SEPARATOR.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write("Gracias por su preferencia!".getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write(ALIGN_LEFT);
    }

    private void writeHeader(ByteArrayOutputStream buffer) throws IOException {
        buffer.write(ALIGN_CENTER);
        buffer.write(DOUBLE_SIZE_ON);
        buffer.write(businessName.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write(NORMAL_SIZE);

        if (businessAddress != null && !businessAddress.isEmpty()) {
            buffer.write(businessAddress.getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }
        if (businessPhone != null && !businessPhone.isEmpty()) {
            buffer.write(("Tel: " + businessPhone).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }
        buffer.write(LINE_FEED);
    }

    private void writeDetails(ByteArrayOutputStream buffer, List<SaleDetailDTO> details) throws IOException {
        if (details == null || details.isEmpty()) {
            buffer.write("(Sin productos)".getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
            return;
        }

        // Encabezado de columnas
        buffer.write(BOLD_ON);
        buffer.write(formatLine("PRODUCTO", "CANT", "PRECIO").getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(BOLD_OFF);
        buffer.write(LINE_FEED);

        for (SaleDetailDTO detail : details) {
            // Nombre del producto (puede ocupar varias líneas)
            String productName = detail.getProductName();
            if (productName != null && productName.length() > 24) {
                productName = productName.substring(0, 24);
            }

            String qty = formatQuantity(detail.getQuantity());
            String price = formatPrice(detail.getSubtotal());

            buffer.write(formatLine(productName, qty, price).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }
    }

    private void writeTotals(ByteArrayOutputStream buffer, TicketDTO ticket) throws IOException {
        buffer.write(ALIGN_RIGHT);

        // Subtotal
        buffer.write(("Subtotal: $" + formatPrice(ticket.getSubtotal())).getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);

        // Descuento (si aplica)
        if (ticket.getDiscountAmount() != null && ticket.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            buffer.write(("Descuento: -$" + formatPrice(ticket.getDiscountAmount())).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        // Total
        buffer.write(BOLD_ON);
        buffer.write(DOUBLE_HEIGHT_ON);
        buffer.write(("TOTAL: $" + formatPrice(ticket.getTotal())).getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(NORMAL_SIZE);
        buffer.write(BOLD_OFF);
        buffer.write(LINE_FEED);

        buffer.write(ALIGN_LEFT);
    }

    /**
     * Formatea una línea con tres columnas para impresora de 80mm.
     */
    private String formatLine(String col1, String col2, String col3) {
        int col1Width = 24;
        int col2Width = 6;
        int col3Width = 10;

        col1 = col1 != null ? col1 : "";
        col2 = col2 != null ? col2 : "";
        col3 = col3 != null ? col3 : "";

        if (col1.length() > col1Width) col1 = col1.substring(0, col1Width);
        if (col2.length() > col2Width) col2 = col2.substring(0, col2Width);
        if (col3.length() > col3Width) col3 = col3.substring(0, col3Width);

        return String.format("%-" + col1Width + "s %" + col2Width + "s %" + col3Width + "s", col1, col2, col3);
    }

    /**
     * Formatea un precio.
     */
    private String formatPrice(BigDecimal price) {
        if (price == null) return "0.00";
        return String.format("%.2f", price);
    }

    /**
     * Formatea una cantidad.
     */
    private String formatQuantity(BigDecimal qty) {
        if (qty == null) return "0";
        if (qty.stripTrailingZeros().scale() <= 0) {
            return qty.toBigInteger().toString();
        }
        return qty.toString();
    }

    /**
     * Imprime un ticket de prueba para verificar la conexión con la impresora.
     *
     * @throws PrinterException Si hay error al imprimir
     * @throws IOException      Si hay error al generar los datos
     */
    public void printTestPage() throws PrinterException, IOException {
        System.out.println("Generando página de prueba...");

        // Determinar el nombre del dispositivo/impresora para mostrar
        String printerInfo = IS_WINDOWS ? getEffectivePrinterName() : printerPath;

        // Generar página de prueba
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(INIT);
        buffer.write(CHARSET_PC850);
        buffer.write(ALIGN_CENTER);

        buffer.write(DOUBLE_SIZE_ON);
        buffer.write("PRUEBA DE IMPRESION".getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(NORMAL_SIZE);
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);

        buffer.write(SEPARATOR.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);

        buffer.write("POS Printer Agent".getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write("Conexion exitosa!".getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);

        buffer.write(("Sistema: " + (IS_WINDOWS ? "Windows" : "Linux")).getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write(("Impresora: " + printerInfo).getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);

        buffer.write(SEPARATOR.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);

        buffer.write(CUT_PAPER_FEED);

        // Imprimir según el sistema operativo
        if (IS_WINDOWS) {
            printToWindowsPrinter(buffer.toByteArray(), "Pagina de prueba");
        } else {
            printToLinuxDevice(buffer.toByteArray(), "Pagina de prueba");
        }
    }

    /**
     * Verifica si la impresora está disponible.
     * En Linux: verifica que el archivo de dispositivo exista y sea escribible.
     * En Windows: verifica que exista un PrintService con el nombre configurado.
     *
     * @return true si la impresora está disponible
     */
    public boolean isAvailable() {
        if (IS_WINDOWS) {
            String effectiveName = getEffectivePrinterName();
            return effectiveName != null && findPrintService(effectiveName) != null;
        } else {
            File printerDevice = new File(printerPath);
            return printerDevice.exists() && printerDevice.canWrite();
        }
    }

    /**
     * Obtiene la ruta del dispositivo de impresora configurado (Linux).
     *
     * @return Ruta del dispositivo (ej: /dev/usb/lp5)
     */
    public String getPrinterPath() {
        return printerPath;
    }

    /**
     * Obtiene el nombre de la impresora configurada (Windows).
     *
     * @return Nombre de la impresora
     */
    public String getPrinterName() {
        return printerName;
    }

    /**
     * Indica si el sistema operativo actual es Windows.
     *
     * @return true si es Windows
     */
    public static boolean isWindows() {
        return IS_WINDOWS;
    }

    /**
     * Excepción personalizada para errores de impresora.
     */
    public static class PrinterException extends Exception {
        public PrinterException(String message) {
            super(message);
        }

        public PrinterException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Excepción de compatibilidad (alias para PrinterException).
     * @deprecated Usar PrinterException en su lugar
     */
    @Deprecated
    public static class USBException extends PrinterException {
        public USBException(String message) {
            super(message);
        }

        public USBException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
