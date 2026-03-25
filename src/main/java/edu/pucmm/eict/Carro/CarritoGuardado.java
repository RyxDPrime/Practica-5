package edu.pucmm.eict.Carro;

import jakarta.persistence.*;

@Entity
public class CarritoGuardado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private Long productoId;

    @Column(nullable = false)
    private int cantidad;

    public CarritoGuardado() {}

    public CarritoGuardado(String username, Long productoId, int cantidad) {
        this.username   = username;
        this.productoId = productoId;
        this.cantidad   = cantidad;
    }

    public String getUsername()  { return username;   }
    public Long   getProductoId(){ return productoId; }
    public int    getCantidad()  { return cantidad;   }
}
