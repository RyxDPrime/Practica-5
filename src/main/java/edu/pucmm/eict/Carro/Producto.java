package edu.pucmm.eict.Carro;

import jakarta.persistence.*;

import java.util.HashSet;
import java.util.Set;

@Entity
public class Producto {

    @OneToMany(mappedBy = "producto",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private final Set<Comentario> comentarios = new HashSet<>();
    @OneToMany(mappedBy = "producto",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private final Set<ImagenProducto> imagenes = new HashSet<>();
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nombre;
    private double precio;
    @Column(length = 2000)
    private String descripcion;

    public Producto() {
    }

    public Producto(String nombre, double precio, String descripcion) {
        this.nombre = nombre;
        this.precio = precio;
        this.descripcion = descripcion;
    }

    public Long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public double getPrecio() {
        return precio;
    }

    public void setPrecio(double precio) {
        this.precio = precio;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public Set<ImagenProducto> getImagenes() {
        return imagenes;
    }

    public Set<Comentario> getComentarios() {
        return comentarios;
    }
}