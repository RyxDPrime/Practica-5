package edu.pucmm.eict.Carro;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Compra {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String cliente;

    private LocalDateTime fecha;

    @OneToMany(mappedBy = "compra", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ItemCarrito> items = new ArrayList<>();

    public Compra() {
    }

    public Compra(String cliente, LocalDateTime fecha) {
        this.cliente = cliente;
        this.fecha = fecha;
    }

    public Long getId() {
        return id;
    }

    public String getCliente() {
        return cliente;
    }

    public List<ItemCarrito> getItems() {
        return items;
    }

    public LocalDateTime getFecha() {
        return fecha;
    }

    public double getTotal() {
        return items.stream()
                .mapToDouble(ItemCarrito::getSubtotal)
                .sum();
    }
}