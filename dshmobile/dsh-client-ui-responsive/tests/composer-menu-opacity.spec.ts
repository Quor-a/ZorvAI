// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { COMPOSER_MENU_CSS } from '../src/client/composer-menu.css.ts'

const source = COMPOSER_MENU_CSS.replace(/\/\*[\s\S]*?\*\//g, '')
const opaqueRule = /(body \[data-dsh-android-model-menu\]\[data-menu-material='translucent'\] > \[aria-hidden='true'\])\s*\{([^}]*)\}/.exec(source)

describe('Android model menu opacity', () => {
  it('makes only the Android bridge-tagged portaled model material opaque at any viewport width', () => {
    expect(opaqueRule, 'the targeted model-menu rule must exist').not.toBeNull()
    const selector = opaqueRule![1]
    const declarations = opaqueRule![2]
    expect(declarations).toContain('background: var(--dsw-alias-bg-module-platform)')
    expect(declarations).toContain('backdrop-filter: none')

    const modelMenu = document.createElement('div')
    modelMenu.setAttribute('role', 'menu')
    modelMenu.setAttribute('data-dsh-android-model-menu', '')
    modelMenu.setAttribute('data-menu-material', 'translucent')
    const material = document.createElement('div')
    material.setAttribute('aria-hidden', 'true')
    modelMenu.append(material)
    document.body.append(modelMenu)
    expect(material.matches(selector)).toBe(true)

    const unrelatedMenu = document.createElement('div')
    unrelatedMenu.setAttribute('role', 'menu')
    const unrelatedMaterial = document.createElement('div')
    unrelatedMaterial.setAttribute('aria-hidden', 'true')
    unrelatedMenu.append(unrelatedMaterial)
    document.body.append(unrelatedMenu)
    expect(unrelatedMaterial.matches(selector)).toBe(false)

    const unrelatedModelMaterial = document.createElement('div')
    unrelatedModelMaterial.setAttribute('aria-hidden', 'true')
    const untaggedPortal = document.createElement('div')
    untaggedPortal.setAttribute('role', 'menu')
    untaggedPortal.setAttribute('data-menu-material', 'translucent')
    untaggedPortal.append(unrelatedModelMaterial)
    document.body.append(untaggedPortal)
    expect(unrelatedModelMaterial.matches(selector)).toBe(false)

    const mobileOnlyMenu = document.createElement('div')
    mobileOnlyMenu.setAttribute('role', 'menu')
    mobileOnlyMenu.setAttribute('data-dsh-mobile-model-menu', '')
    mobileOnlyMenu.setAttribute('data-menu-material', 'translucent')
    const mobileOnlyMaterial = document.createElement('div')
    mobileOnlyMaterial.setAttribute('aria-hidden', 'true')
    mobileOnlyMenu.append(mobileOnlyMaterial)
    document.body.append(mobileOnlyMenu)
    expect(mobileOnlyMaterial.matches(selector)).toBe(false)
    document.body.innerHTML = ''
  })
})
