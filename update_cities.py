import json
import urllib.request
import os
import re

# We will use geonamescache to generate the required dataset dynamically.
import geonamescache

gc = geonamescache.GeonamesCache()
cities = gc.get_cities()
countries = gc.get_countries()

# Create a fast list of cities sorted by population descending
all_cities_sorted = sorted(cities.values(), key=lambda c: c.get('population', 0), reverse=True)

final_cities = []
seen_coords = set()

def add_city(city_obj):
    # To avoid exact duplicates (e.g. same lat/lon)
    lat = city_obj['latitude']
    lon = city_obj['longitude']
    coord_key = (round(lat, 3), round(lon, 3))
    if coord_key in seen_coords:
        return
    seen_coords.add(coord_key)
    
    country_code = city_obj['countrycode']
    country_name = countries.get(country_code, {}).get('name', country_code)
    # Exceptions/Formatting
    if country_code == 'US':
        country_name = 'USA'
    elif country_code == 'GB':
        country_name = 'UK'
        
    name = city_obj['name']
    
    if country_code == 'US':
        state = city_obj.get('admin1code', '')
        if state:
            name = f"{name}, {state}"
    elif country_code == 'BR':
        admin1 = city_obj.get('admin1code', '')
        br_fips = {
            '01': 'AC', '02': 'AL', '03': 'AP', '04': 'AM', '05': 'BA', '06': 'CE', '07': 'DF', '08': 'ES',
            '29': 'GO', '13': 'MA', '14': 'MT', '12': 'MS', '15': 'MG', '16': 'PA', '17': 'PB', '18': 'PR',
            '30': 'PE', '20': 'PI', '21': 'RJ', '22': 'RN', '23': 'RS', '24': 'RO', '25': 'RR', '26': 'SC',
            '27': 'SP', '28': 'SE', '31': 'TO'
        }
        uf = br_fips.get(admin1)
        if uf:
            name = f"{name}, {uf}"
    
    final_cities.append({
        'name': name,
        'country': country_name,
        'lat': lat,
        'lon': lon,
        'tz': city_obj['timezone']
    })

# 1. Top 10 cities of each country in the world
country_city_count = {}
for c in all_cities_sorted:
    cc = c['countrycode']
    count = country_city_count.get(cc, 0)
    if count < 10:
        add_city(c)
        country_city_count[cc] = count + 1

# 2. All Brazilian capitals (27)
# Identifying them by exact name match in Brazil
br_capitals = {
    'Rio Branco', 'Maceió', 'Macapá', 'Manaus', 'Salvador', 'Fortaleza',
    'Brasília', 'Vitória', 'Goiânia', 'São Luís', 'Cuiabá', 'Campo Grande',
    'Belo Horizonte', 'Belém', 'João Pessoa', 'Curitiba', 'Recife',
    'Teresina', 'Rio de Janeiro', 'Natal', 'Porto Alegre', 'Porto Velho',
    'Boa Vista', 'Florianópolis', 'São Paulo', 'Aracaju', 'Palmas'
}
for c in all_cities_sorted:
    if c['countrycode'] == 'BR' and c['name'] in br_capitals:
        add_city(c)

# 3. All US capitals
# Identifying them by exact name and state code
us_capitals = [
    ('Montgomery', 'AL'), ('Juneau', 'AK'), ('Phoenix', 'AZ'), ('Little Rock', 'AR'), ('Sacramento', 'CA'),
    ('Denver', 'CO'), ('Hartford', 'CT'), ('Dover', 'DE'), ('Tallahassee', 'FL'), ('Atlanta', 'GA'),
    ('Honolulu', 'HI'), ('Boise', 'ID'), ('Springfield', 'IL'), ('Indianapolis', 'IN'), ('Des Moines', 'IA'),
    ('Topeka', 'KS'), ('Frankfort', 'KY'), ('Baton Rouge', 'LA'), ('Augusta', 'ME'), ('Annapolis', 'MD'),
    ('Boston', 'MA'), ('Lansing', 'MI'), ('St. Paul', 'MN'), ('Jackson', 'MS'), ('Jefferson City', 'MO'),
    ('Helena', 'MT'), ('Lincoln', 'NE'), ('Carson City', 'NV'), ('Concord', 'NH'), ('Trenton', 'NJ'),
    ('Santa Fe', 'NM'), ('Albany', 'NY'), ('Raleigh', 'NC'), ('Bismarck', 'ND'), ('Columbus', 'OH'),
    ('Oklahoma City', 'OK'), ('Salem', 'OR'), ('Harrisburg', 'PA'), ('Providence', 'RI'), ('Columbia', 'SC'),
    ('Pierre', 'SD'), ('Nashville', 'TN'), ('Austin', 'TX'), ('Salt Lake City', 'UT'), ('Montpelier', 'VT'),
    ('Richmond', 'VA'), ('Olympia', 'WA'), ('Charleston', 'WV'), ('Madison', 'WI'), ('Cheyenne', 'WY')
]

us_state_city_count = {}
for c in all_cities_sorted:
    if c['countrycode'] == 'US':
        state = c.get('admin1code', '')
        
        # Check if it's a capital
        is_cap = False
        for cap_name, cap_state in us_capitals:
            if c['name'] == cap_name and state == cap_state:
                is_cap = True
                break
        if is_cap:
            add_city(c)

# 4. Top 5 cities of each US state
for c in all_cities_sorted:
    if c['countrycode'] == 'US':
        state = c.get('admin1code', '')
        if state:
            count = us_state_city_count.get(state, 0)
            if count < 5:
                add_city(c)
                us_state_city_count[state] = count + 1

# Sort final cities alphabetically by Country then City
final_cities.sort(key=lambda x: (x['country'], x['name']))

print(f"Total cities collected: {len(final_cities)}")

# Build JS constant string
js_array_str = "const CITIES = [\n"
for c in final_cities:
    js_array_str += f"  {{ name: {json.dumps(c['name'])}, country: {json.dumps(c['country'])}, lat: {c['lat']:.4f}, lon: {c['lon']:.4f}, tz: {json.dumps(c['tz'])} }},\n"
js_array_str += "];"

# Read original app.js and replace the CITIES block
target_file = '/home/viking/ThomassenPovoaHoldingAS/StormberryAS/SunApp/app.js'
with open(target_file, 'r', encoding='utf-8') as f:
    content = f.read()

import re
# Use re.sub but with a function replacement to avoid escape sequence evaluation
pattern = r"const CITIES = \[.*?\];"
new_content = re.sub(pattern, lambda _: js_array_str, content, flags=re.DOTALL)

with open(target_file, 'w', encoding='utf-8') as f:
    f.write(new_content)

print(f"Successfully injected {len(final_cities)} cities into app.js!")
