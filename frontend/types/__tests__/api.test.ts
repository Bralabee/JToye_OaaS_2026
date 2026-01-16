import { hasAllergen, toggleAllergen, getAllergenNames, ALLERGENS } from '../api'

describe('Allergen Utility Functions', () => {
  describe('hasAllergen', () => {
    it('should return true when allergen bit is set', () => {
      const mask = 1 << 0 // Gluten bit set
      expect(hasAllergen(mask, 0)).toBe(true)
    })

    it('should return false when allergen bit is not set', () => {
      const mask = 1 << 0 // Only Gluten bit set
      expect(hasAllergen(mask, 1)).toBe(false)
    })

    it('should handle multiple allergens correctly', () => {
      const mask = (1 << 0) | (1 << 2) | (1 << 4) // Gluten, Eggs, Peanuts
      expect(hasAllergen(mask, 0)).toBe(true)
      expect(hasAllergen(mask, 2)).toBe(true)
      expect(hasAllergen(mask, 4)).toBe(true)
      expect(hasAllergen(mask, 1)).toBe(false)
      expect(hasAllergen(mask, 3)).toBe(false)
    })

    it('should return false for mask of 0 (no allergens)', () => {
      expect(hasAllergen(0, 0)).toBe(false)
      expect(hasAllergen(0, 5)).toBe(false)
    })
  })

  describe('toggleAllergen', () => {
    it('should set bit when not present', () => {
      const mask = 0
      const newMask = toggleAllergen(mask, 0)
      expect(hasAllergen(newMask, 0)).toBe(true)
    })

    it('should unset bit when present', () => {
      const mask = 1 << 2 // Eggs bit set
      const newMask = toggleAllergen(mask, 2)
      expect(hasAllergen(newMask, 2)).toBe(false)
    })

    it('should toggle multiple bits independently', () => {
      let mask = 0
      mask = toggleAllergen(mask, 0) // Add Gluten
      mask = toggleAllergen(mask, 4) // Add Peanuts

      expect(hasAllergen(mask, 0)).toBe(true)
      expect(hasAllergen(mask, 4)).toBe(true)

      mask = toggleAllergen(mask, 0) // Remove Gluten

      expect(hasAllergen(mask, 0)).toBe(false)
      expect(hasAllergen(mask, 4)).toBe(true)
    })
  })

  describe('getAllergenNames', () => {
    it('should return empty array for mask of 0', () => {
      expect(getAllergenNames(0)).toEqual([])
    })

    it('should return correct names for single allergen', () => {
      const mask = 1 << 0 // Gluten
      expect(getAllergenNames(mask)).toEqual(['Gluten'])
    })

    it('should return correct names for multiple allergens', () => {
      const mask = (1 << 0) | (1 << 2) | (1 << 6) // Gluten, Eggs, Milk
      const names = getAllergenNames(mask)

      expect(names).toHaveLength(3)
      expect(names).toContain('Gluten')
      expect(names).toContain('Eggs')
      expect(names).toContain('Milk')
    })

    it('should return all allergens when all bits are set', () => {
      const mask = (1 << 14) - 1 // All 14 allergen bits set
      const names = getAllergenNames(mask)

      expect(names).toHaveLength(14)
      expect(names).toContain('Gluten')
      expect(names).toContain('Molluscs')
    })
  })

  describe('ALLERGENS constant', () => {
    it('should contain 14 allergens', () => {
      expect(ALLERGENS).toHaveLength(14)
    })

    it('should have unique bit values', () => {
      const bits = ALLERGENS.map(a => a.bit)
      const uniqueBits = new Set(bits)
      expect(uniqueBits.size).toBe(ALLERGENS.length)
    })

    it('should have all required properties', () => {
      ALLERGENS.forEach(allergen => {
        expect(allergen).toHaveProperty('bit')
        expect(allergen).toHaveProperty('name')
        expect(allergen).toHaveProperty('icon')
        expect(typeof allergen.bit).toBe('number')
        expect(typeof allergen.name).toBe('string')
        expect(typeof allergen.icon).toBe('string')
      })
    })
  })
})
