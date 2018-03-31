package org.example.petstore.model;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "Cat")
public class Cat extends Pet {

    String catBreed;

    public String getCatBreed() {
        return catBreed;
    }

    public void setCatBreed(String catBreed) {
        this.catBreed = catBreed;
    }
}
