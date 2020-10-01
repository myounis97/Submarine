package com.myounis.submarine

class Album(var id: Long, var name: String?) {

    override fun equals(other: Any?): Boolean {
        if (other is Album) {
            return other.id == id
        }
        return false
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

}