package pe.edu.cibertec.solicitud;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootApplication
@EnableFeignClients
public class SolicitudServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SolicitudServiceApplication.class, args);
    }

    @RestController
    @RefreshScope
    @RequestMapping("/api/v1/solicitudes")
    public static class SolicitudPrestamoController {
        private final SolicitudPrestamoService solicitudPrestamoService;

        @Value("${fintech.tasa-interes-base:0.15}")
        private double tasaInteresBase;

        @Value("${fintech.monto-maximo-prestamo:50000.00}")
        private double montoMaximoPrestamo;

        @Value("${fintech.mensaje-comercial:Servicio de Solicitudes Fintech - Grupo XX}")
        private String mensajeComercial;

        @Value("${fintech.comision-desembolso:50.00}")
        private double comisionDesembolso;

        @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/fintech_solicitudes_db}")
        private String datasourceUrl;

        public SolicitudPrestamoController(SolicitudPrestamoService solicitudPrestamoService) {
            this.solicitudPrestamoService = solicitudPrestamoService;
        }

        @GetMapping("/configuracion")
        public Map<String, Object> configuracion() {
            Map<String, Object> response = new HashMap<>();
            response.put("tasa-interes-base", tasaInteresBase);
            response.put("monto-maximo-prestamo", montoMaximoPrestamo);
            response.put("mensaje-comercial", mensajeComercial);
            response.put("comision-desembolso", comisionDesembolso);
            response.put("datasource.url", datasourceUrl);
            return response;
        }

        @GetMapping("/{idSolicitud}")
        public SolicitudPrestamoResponseDTO obtenerSolicitud(@PathVariable Long idSolicitud) {
            return solicitudPrestamoService.obtenerSolicitud(idSolicitud);
        }

        @GetMapping("/evaluar-y-aprobar/{idSolicitud}")
        public SolicitudPrestamoResponseDTO evaluarYaprobar(@PathVariable Long idSolicitud) {
            return solicitudPrestamoService.evaluarYaprobar(idSolicitud);
        }

        @GetMapping("/resumen-cliente/{clienteId}")
        public ResumenCrediticioClienteDTO resumenCliente(@PathVariable Long clienteId) {
            return solicitudPrestamoService.resumenCliente(clienteId);
        }
    }

    @Service
    public static class SolicitudPrestamoService {
        private final Map<Long, SolicitudPrestamoResponseDTO> solicitudes = new ConcurrentHashMap<>();
        private final EvaluacionClient evaluacionClient;

        public SolicitudPrestamoService(EvaluacionClient evaluacionClient) {
            this.evaluacionClient = evaluacionClient;
            inicializarSolicitudes();
        }

        private void inicializarSolicitudes() {
            SolicitudPrestamoResponseDTO solicitud1 = new SolicitudPrestamoResponseDTO();
            solicitud1.setIdSolicitud(1L);
            solicitud1.setCodigoSolicitud("SOL-2024-001");
            solicitud1.setFechaSolicitud(LocalDateTime.now().minusDays(12));
            solicitud1.setClienteId(1001L);
            solicitud1.setMontoSolicitado(18000.00);
            solicitud1.setPlazoMeses(24);
            solicitud1.setEstadoSolicitud("EN_EVALUACION");
            solicitud1.setTasaInteresAnual(0.15);
            solicitud1.setListaCuotasProyectadas(calcularCuotas(18000.00, 24, 0.15));
            solicitud1.setMontoTotalPagar(solicitud1.getListaCuotasProyectadas().stream().mapToDouble(CuotaPrestamoDTO::getMontoCuota).sum());
            solicitudes.put(1L, solicitud1);

            SolicitudPrestamoResponseDTO solicitud2 = new SolicitudPrestamoResponseDTO();
            solicitud2.setIdSolicitud(2L);
            solicitud2.setCodigoSolicitud("SOL-2024-002");
            solicitud2.setFechaSolicitud(LocalDateTime.now().minusDays(8));
            solicitud2.setClienteId(1002L);
            solicitud2.setMontoSolicitado(25000.00);
            solicitud2.setPlazoMeses(36);
            solicitud2.setEstadoSolicitud("EN_EVALUACION");
            solicitud2.setTasaInteresAnual(0.15);
            solicitud2.setListaCuotasProyectadas(calcularCuotas(25000.00, 36, 0.15));
            solicitud2.setMontoTotalPagar(solicitud2.getListaCuotasProyectadas().stream().mapToDouble(CuotaPrestamoDTO::getMontoCuota).sum());
            solicitudes.put(2L, solicitud2);
        }

        public SolicitudPrestamoResponseDTO obtenerSolicitud(Long idSolicitud) {
            SolicitudPrestamoResponseDTO solicitud = solicitudes.get(idSolicitud);
            if (solicitud == null) {
                throw new NoSuchElementException("Solicitud no encontrada: " + idSolicitud);
            }
            return solicitud;
        }

        public SolicitudPrestamoResponseDTO evaluarYaprobar(Long idSolicitud) {
            SolicitudPrestamoResponseDTO solicitud = obtenerSolicitud(idSolicitud);
            ScoringRiesgoDTO scoring = evaluacionClient.obtenerScoring(solicitud.getClienteId());

            boolean cumpleMonto = solicitud.getMontoSolicitado() <= scoring.getCapacidadMaxEndeudamiento();
            boolean cumpleRiesgo = "BAJO".equalsIgnoreCase(scoring.getNivelRiesgo()) || "MEDIO".equalsIgnoreCase(scoring.getNivelRiesgo());

            solicitud.setEstadoSolicitud((cumpleMonto && cumpleRiesgo) ? "APROBADO" : "RECHAZADO_POR_RIESGO");
            solicitudes.put(idSolicitud, solicitud);
            return solicitud;
        }

        public ResumenCrediticioClienteDTO resumenCliente(Long clienteId) {
            ScoringRiesgoDTO scoring = evaluacionClient.obtenerScoring(clienteId);
            List<SolicitudPrestamoResponseDTO> historial = solicitudes.values().stream()
                    .filter(s -> s.getClienteId().equals(clienteId))
                    .toList();

            String estado = historial.stream().anyMatch(s -> "APROBADO".equalsIgnoreCase(s.getEstadoSolicitud())) ? "APTO" : "NO_APTO";

            ResumenCrediticioClienteDTO resumen = new ResumenCrediticioClienteDTO();
            resumen.setClienteId(clienteId);
            resumen.setNombreCliente("Cliente " + clienteId);
            resumen.setDni("DNI-" + clienteId);
            resumen.setScoringActual(scoring);
            resumen.setHistorialSolicitudes(historial);
            resumen.setEstadoCrediticioGlobal(estado);
            return resumen;
        }

        private List<CuotaPrestamoDTO> calcularCuotas(Double montoSolicitado, Integer plazoMeses, Double tasaInteresAnual) {
            List<CuotaPrestamoDTO> cuotas = new ArrayList<>();
            double tasaMensual = tasaInteresAnual / 12.0;
            double cuotaMensual = montoSolicitado * (tasaMensual / (1 - Math.pow(1 + tasaMensual, -plazoMeses)));
            double saldo = montoSolicitado;

            for (int i = 1; i <= plazoMeses; i++) {
                double interes = saldo * tasaMensual;
                double capital = cuotaMensual - interes;
                saldo -= capital;

                CuotaPrestamoDTO cuota = new CuotaPrestamoDTO();
                cuota.setNumeroCuota(i);
                cuota.setFechaVencimiento(LocalDate.now().plusMonths(i));
                cuota.setMontoCuota(cuotaMensual);
                cuota.setInteres(interes);
                cuota.setCapital(capital);
                cuota.setEstadoCuota("PENDIENTE");
                cuotas.add(cuota);
            }
            return cuotas;
        }
    }

    @FeignClient(name = "EVALUACION-SERVICE-GRUPO12")
    public interface EvaluacionClient {
        @GetMapping("/api/v1/evaluaciones/scoring/{clienteId}")
        ScoringRiesgoDTO obtenerScoring(@PathVariable("clienteId") Long clienteId);
    }

    public static class CuotaPrestamoDTO {
        private Integer numeroCuota;
        private LocalDate fechaVencimiento;
        private Double montoCuota;
        private Double interes;
        private Double capital;
        private String estadoCuota;

        public Integer getNumeroCuota() {
            return numeroCuota;
        }

        public void setNumeroCuota(Integer numeroCuota) {
            this.numeroCuota = numeroCuota;
        }

        public LocalDate getFechaVencimiento() {
            return fechaVencimiento;
        }

        public void setFechaVencimiento(LocalDate fechaVencimiento) {
            this.fechaVencimiento = fechaVencimiento;
        }

        public Double getMontoCuota() {
            return montoCuota;
        }

        public void setMontoCuota(Double montoCuota) {
            this.montoCuota = montoCuota;
        }

        public Double getInteres() {
            return interes;
        }

        public void setInteres(Double interes) {
            this.interes = interes;
        }

        public Double getCapital() {
            return capital;
        }

        public void setCapital(Double capital) {
            this.capital = capital;
        }

        public String getEstadoCuota() {
            return estadoCuota;
        }

        public void setEstadoCuota(String estadoCuota) {
            this.estadoCuota = estadoCuota;
        }
    }

    public static class SolicitudPrestamoResponseDTO {
        private Long idSolicitud;
        private String codigoSolicitud;
        private LocalDateTime fechaSolicitud;
        private Long clienteId;
        private Double montoSolicitado;
        private Integer plazoMeses;
        private String estadoSolicitud;
        private List<CuotaPrestamoDTO> listaCuotasProyectadas;
        private Double tasaInteresAnual;
        private Double montoTotalPagar;

        public Long getIdSolicitud() {
            return idSolicitud;
        }

        public void setIdSolicitud(Long idSolicitud) {
            this.idSolicitud = idSolicitud;
        }

        public String getCodigoSolicitud() {
            return codigoSolicitud;
        }

        public void setCodigoSolicitud(String codigoSolicitud) {
            this.codigoSolicitud = codigoSolicitud;
        }

        public LocalDateTime getFechaSolicitud() {
            return fechaSolicitud;
        }

        public void setFechaSolicitud(LocalDateTime fechaSolicitud) {
            this.fechaSolicitud = fechaSolicitud;
        }

        public Long getClienteId() {
            return clienteId;
        }

        public void setClienteId(Long clienteId) {
            this.clienteId = clienteId;
        }

        public Double getMontoSolicitado() {
            return montoSolicitado;
        }

        public void setMontoSolicitado(Double montoSolicitado) {
            this.montoSolicitado = montoSolicitado;
        }

        public Integer getPlazoMeses() {
            return plazoMeses;
        }

        public void setPlazoMeses(Integer plazoMeses) {
            this.plazoMeses = plazoMeses;
        }

        public String getEstadoSolicitud() {
            return estadoSolicitud;
        }

        public void setEstadoSolicitud(String estadoSolicitud) {
            this.estadoSolicitud = estadoSolicitud;
        }

        public List<CuotaPrestamoDTO> getListaCuotasProyectadas() {
            return listaCuotasProyectadas;
        }

        public void setListaCuotasProyectadas(List<CuotaPrestamoDTO> listaCuotasProyectadas) {
            this.listaCuotasProyectadas = listaCuotasProyectadas;
        }

        public Double getTasaInteresAnual() {
            return tasaInteresAnual;
        }

        public void setTasaInteresAnual(Double tasaInteresAnual) {
            this.tasaInteresAnual = tasaInteresAnual;
        }

        public Double getMontoTotalPagar() {
            return montoTotalPagar;
        }

        public void setMontoTotalPagar(Double montoTotalPagar) {
            this.montoTotalPagar = montoTotalPagar;
        }
    }

    public static class ScoringRiesgoDTO {
        private Long clienteId;
        private Integer puntajeCrediticio;
        private String nivelRiesgo;
        private Double capacidadMaxEndeudamiento;
        private String dictamenRiesgo;

        public Long getClienteId() {
            return clienteId;
        }

        public void setClienteId(Long clienteId) {
            this.clienteId = clienteId;
        }

        public Integer getPuntajeCrediticio() {
            return puntajeCrediticio;
        }

        public void setPuntajeCrediticio(Integer puntajeCrediticio) {
            this.puntajeCrediticio = puntajeCrediticio;
        }

        public String getNivelRiesgo() {
            return nivelRiesgo;
        }

        public void setNivelRiesgo(String nivelRiesgo) {
            this.nivelRiesgo = nivelRiesgo;
        }

        public Double getCapacidadMaxEndeudamiento() {
            return capacidadMaxEndeudamiento;
        }

        public void setCapacidadMaxEndeudamiento(Double capacidadMaxEndeudamiento) {
            this.capacidadMaxEndeudamiento = capacidadMaxEndeudamiento;
        }

        public String getDictamenRiesgo() {
            return dictamenRiesgo;
        }

        public void setDictamenRiesgo(String dictamenRiesgo) {
            this.dictamenRiesgo = dictamenRiesgo;
        }
    }

    public static class ResumenCrediticioClienteDTO {
        private Long clienteId;
        private String nombreCliente;
        private String dni;
        private ScoringRiesgoDTO scoringActual;
        private List<SolicitudPrestamoResponseDTO> historialSolicitudes;
        private String estadoCrediticioGlobal;

        public Long getClienteId() {
            return clienteId;
        }

        public void setClienteId(Long clienteId) {
            this.clienteId = clienteId;
        }

        public String getNombreCliente() {
            return nombreCliente;
        }

        public void setNombreCliente(String nombreCliente) {
            this.nombreCliente = nombreCliente;
        }

        public String getDni() {
            return dni;
        }

        public void setDni(String dni) {
            this.dni = dni;
        }

        public ScoringRiesgoDTO getScoringActual() {
            return scoringActual;
        }

        public void setScoringActual(ScoringRiesgoDTO scoringActual) {
            this.scoringActual = scoringActual;
        }

        public List<SolicitudPrestamoResponseDTO> getHistorialSolicitudes() {
            return historialSolicitudes;
        }

        public void setHistorialSolicitudes(List<SolicitudPrestamoResponseDTO> historialSolicitudes) {
            this.historialSolicitudes = historialSolicitudes;
        }

        public String getEstadoCrediticioGlobal() {
            return estadoCrediticioGlobal;
        }

        public void setEstadoCrediticioGlobal(String estadoCrediticioGlobal) {
            this.estadoCrediticioGlobal = estadoCrediticioGlobal;
        }
    }
}
